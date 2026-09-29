package com.verisonder.sondereye.globe

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.verisonder.sondereye.core.Astro
import com.verisonder.sondereye.core.CameraState
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.M4
import com.verisonder.sondereye.core.TileKey
import com.verisonder.sondereye.core.TileMesh
import com.verisonder.sondereye.core.TileSelect
import com.verisonder.sondereye.core.TileSource
import com.verisonder.sondereye.core.V3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One marker, prepared on the UI thread. */
class Marker(
    /** Stable across refreshes ("q:us7000abcd", "f:3c6dd4"…); selection follows it. */
    val key: String,
    val lat: Double,
    val lon: Double,
    val sizePx: Float,
    val rgb: Int,
    val shape: Int = SHAPE_DOT,
    /** Degrees clockwise from north for shapes that point somewhere; NaN for none. */
    val bearing: Double = Double.NaN,
    val altM: Double = 0.0,
) {
    val pos: V3 = Geo.ecef(lat, lon, altM)

    companion object {
        const val SHAPE_DOT = 0
        const val SHAPE_PLANE = 1
        const val SHAPE_SAT = 2
        const val SHAPE_ME = 3
    }
}

/** A line through world space (an orbit, a flight's trail), metres. */
/** A polyline; with [pairs], separate segments (each two points), all in one draw. */
class GlobeLine(val points: List<V3>, val rgb: Int, val alpha: Float = 1f, val pairs: Boolean = false)

data class GlobeStatus(
    /** Tiles on screen still showing a blurrier parent or nothing. */
    val loading: Int,
    /** Tile downloads that failed since the last one that worked. */
    val failures: Int,
    val lastFailure: String?,
)

/**
 * Draws on the GL thread. Everything it needs from the UI thread arrives through
 * volatile fields or queues; nothing here touches views.
 */
class GlobeRenderer(
    cacheDir: File,
    assets: android.content.res.AssetManager,
    private val camera: () -> CameraState,
    private val density: Float,
    private val requestRender: () -> Unit,
    private val onStatus: (GlobeStatus) -> Unit,
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var markers: List<Marker> = emptyList()
    @Volatile var selectedKey: String? = null
    /** Lines drawn over the globe: orbits, trails. */
    @Volatile var lines: List<GlobeLine> = emptyList()
    /** Shade the night side (and show night lights where that overlay is on). */
    @Volatile var dayNight: Boolean = false

    /** The map underneath. */
    @Volatile var base: TileSource = TileSource.SATELLITE
    /** Transparent layers on top of it (roads, labels, radar), in drawing order. */
    @Volatile var overlays: List<TileSource> = emptyList()

    private val uploads = ConcurrentLinkedQueue<Pair<SourcedTile, Bitmap>>()
    private val absentQueue = ConcurrentLinkedQueue<SourcedTile>()
    /** Tiles the source has nothing for. GL thread only. */
    private val absent = HashSet<SourcedTile>()
    @Volatile private var failures = 0
    @Volatile private var lastFailure: String? = null

    private val loader = TileLoader(
        cacheDir = cacheDir,
        assets = assets,
        onLoaded = { t, b ->
            uploads.add(t to b)
            failures = 0
            requestRender()
        },
        onAbsent = { t ->
            absentQueue.add(t)
            requestRender()
        },
        onFailed = { _, msg ->
            failures++
            lastFailure = msg
            requestRender()
        },
    )

    private var width = 1
    private var height = 1
    private var broken = false

    // GL objects. All ids die with the context; onSurfaceCreated starts from scratch.
    private var tileProg = 0
    private var pointProg = 0
    private var glowProg = 0
    private val indexBuffers = HashMap<Int, Pair<Int, Int>>() // segments -> (ibo, count)
    private val textures = LinkedHashMap<SourcedTile, Int>(64, 0.75f, true)
    private val meshes = LinkedHashMap<TileKey, Int>(64, 0.75f, true)
    private var capVbo = 0
    private var capCount = 0
    private var quadVbo = 0
    private var pointVbo = 0
    private val mvp = FloatArray(16)
    private var lastStatus: GlobeStatus? = null

    // ---- Lifecycle ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context means every old id is gone; forget them without deleting.
        textures.clear()
        meshes.clear()
        absent.clear()
        indexBuffers.clear()
        pathVbo = 0
        quadVbo2 = 0
        broken = false
        try {
            tileProg = program(TILE_VS, TILE_FS, "imagery")
            pointProg = program(POINT_VS, POINT_FS, "markers")
            glowProg = program(GLOW_VS, GLOW_FS, "atmosphere")
        } catch (e: IllegalStateException) {
            broken = true
            onError(e.message ?: "Globe: shaders failed")
            return
        }
        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        capVbo = ids[0]; quadVbo = ids[1]; pointVbo = ids[2]
        buildCaps()
        upload(quadVbo, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        GLES30.glClearColor(SPACE_R, SPACE_G, SPACE_B, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(1, w)
        height = maxOf(1, h)
        GLES30.glViewport(0, 0, width, height)
    }

    fun shutdown() {
        loader.shutdown()
        while (true) {
            val (_, bmp) = uploads.poll() ?: break
            bmp.recycle()
        }
    }

    // ---- Frame ----------------------------------------------------------------------

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (broken) return
        loader.nextFrame()
        uploadPending()

        val view = camera().view(width, height)
        drawGlow(view)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Farthest from the screen centre first: downloads are last-in-first-out, so the
        // tiles you are looking at are fetched first.
        val cx = width / 2.0
        val cy = height / 2.0
        val tiles = TileSelect.select(view, MAX_ZOOM, SPLIT_PX, TILE_LIMIT).sortedByDescending { k ->
            view.project(k.center())?.let { (it[0] - cx) * (it[0] - cx) + (it[1] - cy) * (it[1] - cy) } ?: Double.MAX_VALUE
        }
        /** Tiles whose own base imagery has arrived: overlays wait for these. */
        val baseReady = HashSet<TileKey>()
        usedThisFrame.clear()
        var loading = 0
        GLES30.glUseProgram(tileProg)
        val uMvp = GLES30.glGetUniformLocation(tileProg, "uMvp")
        val uUv = GLES30.glGetUniformLocation(tileProg, "uUv")
        val uHasTex = GLES30.glGetUniformLocation(tileProg, "uHasTex")
        val uColor = GLES30.glGetUniformLocation(tileProg, "uColor")
        val uAlpha = GLES30.glGetUniformLocation(tileProg, "uAlpha")
        val uCenter = GLES30.glGetUniformLocation(tileProg, "uCenter")
        val uSun = GLES30.glGetUniformLocation(tileProg, "uSun")
        val uShade = GLES30.glGetUniformLocation(tileProg, "uShade")
        val uMode = GLES30.glGetUniformLocation(tileProg, "uMode")
        val shading = dayNight
        val sun = Astro.sun(System.currentTimeMillis()).norm()
        GLES30.glUniform3f(uSun, sun.x.toFloat(), sun.y.toFloat(), sun.z.toFloat())
        GLES30.glUniform1f(uShade, if (shading) 1f else 0f)
        // Night is for the planet view. Closer in, you came to see the place: the dark side
        // lightens and the city lights (only country-scale sharp) fade out.
        val alt = view.cam.alt
        val zoomedOut = ((alt - 60_000.0) / (1_500_000.0 - 60_000.0)).coerceIn(0.0, 1.0)
        val nightFloor = (0.75 - 0.59 * zoomedOut).toFloat() // 0.16 from space, 0.75 over a city
        val lightsK = (((alt - 250_000.0) / (1_200_000.0 - 250_000.0)).coerceIn(0.0, 1.0)).toFloat()
        val uFloor = GLES30.glGetUniformLocation(tileProg, "uFloor")
        GLES30.glUniform1f(uFloor, nightFloor)
        GLES30.glUniform1f(uMode, 0f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(tileProg, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1f(uAlpha, 1f)

        val baseSrc = base
        // Night lights only make sense with the night side shaded.
        val overlaySrcs = overlays.filter {
            (!it.night || (shading && lightsK > 0f)) &&
                // Roads are unreadable from far out and would cost a tile per tile: regional zoom only.
                !(it.id == "esri-roads" && alt > ROADS_MAX_ALT)
        }
        // Base first, opaque; then each overlay over it on the same meshes.
        for ((pass, src) in (listOf(baseSrc) + overlaySrcs).withIndex()) {
            if (pass >= 1) {
                GLES30.glEnable(GLES30.GL_BLEND)
                // Lights add to the dark ground; everything else is laid over it.
                if (src.night) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
                else GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            GLES30.glUniform1f(uAlpha, if (src.night) src.alpha * lightsK else src.alpha)
            GLES30.glUniform1f(uMode, if (src.night) 1f else 0f)
            // Only the picture itself goes dark at night; roads, names and radar stay readable.
            GLES30.glUniform1f(uShade, if (shading && pass == 0) 1f else 0f)
            for (k in tiles) {
                val want = SourcedTile(src, src.keyFor(k))
                var found: SourcedTile? = null
                // Roads, labels and radar only download once the picture under them has:
                // the photo is what you are waiting for.
                val mayRequest = pass == 0 || k in baseReady || src.night
                if (textures[want] != null) { // get(): marks it recently used
                    found = want
                    if (pass == 0) baseReady.add(k)
                } else if (!mayRequest) {
                    found = loadedAncestor(src, want.key)
                } else {
                    // Missing, or failing for now: either way the levels above stand in.
                    val gone = want in absent || loader.failedRecently(want)
                    if (pass == 0 && gone) baseReady.add(k) // nothing finer to wait for here
                    if (!gone) {
                        loading++
                        loader.request(want)
                        // Coarse before fine: the parent arrives first and fills in quickly.
                        want.key.parent()?.let { p ->
                            val pt = SourcedTile(src, p)
                            if (!textures.containsKey(pt) && pt !in absent) loader.request(pt)
                        }
                    } else if (requestNearest(src, want.key)) loading++
                    found = loadedAncestor(src, want.key)
                }
                if (pass == 0 && found?.key != want.key) {
                    // The bundled Blue Marble fills in wherever it is sharper than what has arrived.
                    val bm = TileSource.BLUE_MARBLE
                    val bmWant = SourcedTile(bm, bm.keyFor(k))
                    val bmFound = if (textures[bmWant] != null) bmWant else {
                        if (bmWant !in absent) loader.request(bmWant)
                        loadedAncestor(bm, bmWant.key)
                    }
                    if (bmFound != null && bmFound.key.z > (found?.key?.z ?: -1)) found = bmFound
                }
                if (found == null && src.transparent) continue // nothing to lay over this tile yet
                val c = k.center()
                M4.toFloat(view.mvp(c), mvp)
                GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                GLES30.glUniform3f(uCenter, c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
                if (found != null) {
                    usedThisFrame.add(found)
                    val uv = k.uvIn(found.key)
                    GLES30.glUniform3f(uUv, uv[0].toFloat(), uv[1].toFloat(), uv[2].toFloat())
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[found]!!)
                    GLES30.glUniform1f(uHasTex, 1f)
                } else {
                    GLES30.glUniform3f(uUv, 1f, 0f, 0f)
                    GLES30.glUniform1f(uHasTex, 0f)
                    GLES30.glUniform3f(uColor, OCEAN_R, OCEAN_G, OCEAN_B)
                }
                drawMesh(mesh(k), TileMesh.segments(k.z))
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uMode, 0f)
        GLES30.glUniform1f(uShade, if (shading) 1f else 0f) // for the polar caps

        drawCaps(view, uMvp, uHasTex, uColor, uCenter)
        GLES30.glUniform1f(uShade, 0f) // lines keep their colour day and night
        drawLines(view, uMvp, uHasTex, uColor, uAlpha, uCenter)
        drawMarkers(view)
        trimTextures()
        trim(meshes, MESH_CAP) { GLES30.glDeleteBuffers(1, intArrayOf(it), 0) }

        if (uploads.isNotEmpty()) requestRender()
        val status = GlobeStatus(loading, failures, if (failures > 0) lastFailure else null)
        if (status != lastStatus) {
            lastStatus = status
            onStatus(status)
        }
    }

    /**
     * [k] has nothing (missing, or failing for now). Asks for the nearest ancestor that may, to be stretched over it:
     * reference layers (roads, names) stop several zooms before the imagery does, and without
     * this only the direct parent was ever asked, so zooming past both left the layer empty.
     * True when a download was started.
     */
    private fun requestNearest(src: TileSource, k: TileKey): Boolean {
        var a = k.parent()
        while (a != null) {
            val t = SourcedTile(src, a)
            if (textures.containsKey(t)) return false // already here: loadedAncestor draws it
            if (t !in absent && !loader.failedRecently(t)) {
                loader.request(t)
                return true
            }
            a = a.parent()
        }
        return false
    }

    /** Nearest ancestor of [k] whose texture is loaded, for [src]. */
    private fun loadedAncestor(src: TileSource, k: TileKey): SourcedTile? {
        var a = k.parent()
        while (a != null) {
            val t = SourcedTile(src, a)
            // get(), not containsKey(): only get() marks it as recently used, and a parent
            // standing in for missing tiles must be the last thing the cache drops.
            if (textures[t] != null) return t
            a = a.parent()
        }
        return null
    }

    private fun uploadPending() {
        while (true) absent.add(absentQueue.poll() ?: break)
        if (absent.size > 20_000) absent.clear() // re-learned on demand; keeps memory flat
        var n = 0
        while (n < UPLOADS_PER_FRAME) {
            val (k, bmp) = uploads.poll() ?: break
            loader.uploaded()
            if (!textures.containsKey(k)) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                textures[k] = ids[0]
            }
            // Cap colour from the bundled globe (always there), refined by the chosen map when it arrives.
            if ((k.source === base || k.source === TileSource.BLUE_MARBLE) && k.key.z == TileSelect.ROOT_Z) sampleCapColour(k.key, bmp)
            bmp.recycle()
            n++
        }
    }

    // ---- Tiles ----------------------------------------------------------------------

    private fun mesh(k: TileKey): Int = meshes.getOrPut(k) {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        upload(ids[0], TileMesh.vertices(k))
        ids[0]
    }

    private fun drawMesh(vbo: Int, segments: Int) {
        val (ibo, count) = indexBuffers.getOrPut(segments) {
            val idx = TileMesh.indices(segments)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[0])
            val buf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(idx)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, buf, GLES30.GL_STATIC_DRAW)
            ids[0] to idx.size
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    // ---- Polar caps -----------------------------------------------------------------
    // Web Mercator stops at 85.05°. The caps close the globe there with a flat colour
    // averaged from the imagery's own edge row, so they blend in whatever the provider
    // shows there (open Arctic water in the north, ice in the south).

    /** Per pole, per root tile column: summed r, g, b and a pixel count. */
    private val capSums = Array(2) { Array(1 shl TileSelect.ROOT_Z) { DoubleArray(4) } }
    private val capRgb = arrayOf(floatArrayOf(CAP_R, CAP_G, CAP_B), floatArrayOf(CAP_R, CAP_G, CAP_B))

    private fun sampleCapColour(k: TileKey, bmp: Bitmap) {
        val last = (1 shl k.z) - 1
        val pole = when (k.y) { 0 -> 0; last -> 1; else -> return }
        val row = if (pole == 0) 0 else bmp.height - 1
        val px = IntArray(bmp.width)
        bmp.getPixels(px, 0, bmp.width, 0, row, bmp.width, 1)
        val sum = capSums[pole][k.x]
        sum.fill(0.0)
        for (c in px) {
            sum[0] += (c shr 16) and 0xFF; sum[1] += (c shr 8) and 0xFF; sum[2] += c and 0xFF; sum[3] += 1.0
        }
        var r = 0.0; var g = 0.0; var b = 0.0; var count = 0.0
        for (col in capSums[pole]) { r += col[0]; g += col[1]; b += col[2]; count += col[3] }
        if (count > 0) capRgb[pole] = floatArrayOf((r / count / 255).toFloat(), (g / count / 255).toFloat(), (b / count / 255).toFloat())
    }

    private fun buildCaps() {
        val seg = 64
        val out = ArrayList<Float>()
        for (sign in intArrayOf(1, -1)) {
            val pole = Geo.ecef(90.0 * sign, 0.0)
            for (i in 0 until seg) {
                val a = Geo.ecef(Geo.MAX_LAT * sign, -180.0 + 360.0 * i / seg) - pole
                val b = Geo.ecef(Geo.MAX_LAT * sign, -180.0 + 360.0 * (i + 1) / seg) - pole
                for (p in listOf(V3(0.0, 0.0, 0.0), a, b)) {
                    out.add(p.x.toFloat()); out.add(p.y.toFloat()); out.add(p.z.toFloat()); out.add(0f); out.add(0f)
                }
            }
        }
        capCount = seg * 3
        upload(capVbo, out.toFloatArray())
    }

    private fun drawCaps(view: com.verisonder.sondereye.core.View, uMvp: Int, uHasTex: Int, uColor: Int, uCenter: Int) {
        GLES30.glUniform1f(uHasTex, 0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, capVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        for ((i, sign) in intArrayOf(1, -1).withIndex()) {
            val c = capRgb[i]
            GLES30.glUniform3f(uColor, c[0], c[1], c[2])
            val pole = Geo.ecef(90.0 * sign, 0.0)
            GLES30.glUniform3f(uCenter, pole.x.toFloat(), pole.y.toFloat(), pole.z.toFloat())
            M4.toFloat(view.mvp(pole), mvp)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, i * capCount, capCount)
        }
    }

    // ---- Atmosphere -----------------------------------------------------------------

    private fun drawGlow(view: com.verisonder.sondereye.core.View) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glUseProgram(glowProg)
        // Looking straight down, the Earth's centre is always the screen centre.
        GLES30.glUniform2f(GLES30.glGetUniformLocation(glowProg, "uCenter"), width / 2f, height / 2f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glowProg, "uRadius"), view.earthRadiusPx().coerceAtMost(1e6).toFloat())
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- Markers --------------------------------------------------------------------

    private var pointScratch = FloatArray(0)

    /**
     * Markers are small quads, one instance each. Things on the ground lie flat in the
     * surface's tangent plane, so they follow the curve of the planet (discs turn into
     * ovals towards the edge, and hide behind it) instead of floating towards the viewer.
     * Things in orbit face the screen. Either way they keep the same size in pixels.
     */
    private fun drawMarkers(view: com.verisonder.sondereye.core.View) {
        val list = markers
        val selKey = selectedKey
        val sel = if (selKey == null) null else list.firstOrNull { it.key == selKey }
        val count = list.size + if (sel != null) 1 else 0
        if (count == 0) return
        val stride = 11 // x y z size r g b ring shape bearing billboard
        if (pointScratch.size < count * stride) pointScratch = FloatArray(count * stride)
        val a = pointScratch
        var o = 0
        val eye = view.eye
        fun put(m: Marker, ring: Boolean) {
            // Relative to the eye in double, then float: no jitter when close.
            a[o++] = (m.pos.x - eye.x).toFloat(); a[o++] = (m.pos.y - eye.y).toFloat(); a[o++] = (m.pos.z - eye.z).toFloat()
            a[o++] = (if (ring) m.sizePx + 14f * density else m.sizePx)
            a[o++] = ((m.rgb shr 16) and 0xFF) / 255f
            a[o++] = ((m.rgb shr 8) and 0xFF) / 255f
            a[o++] = (m.rgb and 0xFF) / 255f
            a[o++] = if (ring) 1f else 0f
            a[o++] = m.shape.toFloat()
            a[o++] = if (m.bearing.isNaN()) 0f else Geo.toRad(m.bearing).toFloat()
            a[o++] = if (m.altM > ORBIT_M) 1f else 0f
        }
        for (m in list) put(m, false)
        if (sel != null) put(sel, true)

        if (quadVbo2 == 0) {
            val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); quadVbo2 = ids[0]
            upload(quadVbo2, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(pointProg)
        M4.toFloat(view.projRot, mvp)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pointProg, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProg, "uOutline"), 1.2f * density)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(pointProg, "uEye"), eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(pointProg, "uRight"), view.right.x.toFloat(), view.right.y.toFloat(), view.right.z.toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(pointProg, "uUp"), view.up.x.toFloat(), view.up.y.toFloat(), view.up.z.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProg, "uPx"), (1.0 / view.focalPx).toFloat())

        // Per-vertex: the quad's corner.
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo2)
        GLES30.glEnableVertexAttribArray(7)
        GLES30.glVertexAttribPointer(7, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(7, 0)
        // Per-instance: the marker.
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * stride * 4, floats(a, count * stride), GLES30.GL_STREAM_DRAW)
        val b = stride * 4
        val attrs = intArrayOf(3, 1, 3, 1, 1, 1, 1) // sizes of locations 0..6
        var off = 0
        for ((loc, n) in attrs.withIndex()) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, n, GLES30.GL_FLOAT, false, b, off * 4)
            GLES30.glVertexAttribDivisor(loc, 1)
            off += n
        }
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        // Other draws read locations 0 and 1 per vertex: undo the instancing state.
        for (loc in 0..6) GLES30.glVertexAttribDivisor(loc, 0)
        for (loc in 2..7) GLES30.glDisableVertexAttribArray(loc)
        GLES30.glDepthMask(true)
    }

    private var quadVbo2 = 0

    // ---- Lines (orbits, trails) --------------------------------------------------------

    private var pathVbo = 0
    private var lineScratch = FloatArray(0)

    private fun drawLines(view: com.verisonder.sondereye.core.View, uMvp: Int, uHasTex: Int, uColor: Int, uAlpha: Int, uCenter: Int) {
        val all = lines
        if (all.isEmpty()) return
        if (pathVbo == 0) {
            val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); pathVbo = ids[0]
        }
        val eye = view.eye
        M4.toFloat(view.projRot, mvp)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uHasTex, 0f)
        GLES30.glUniform3f(uCenter, eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())
        GLES30.glLineWidth(lineWidth)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        for (line in all) {
            val pts = line.points
            if (pts.size < 2) continue
            if (lineScratch.size < pts.size * 5) lineScratch = FloatArray(pts.size * 5 * 2) // reused: bus networks are big
            val data = lineScratch
            var o = 0
            for (p in pts) {
                data[o++] = (p.x - eye.x).toFloat(); data[o++] = (p.y - eye.y).toFloat(); data[o++] = (p.z - eye.z).toFloat()
                data[o++] = 0f; data[o++] = 0f
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pathVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, o * 4, floats(data, o), GLES30.GL_STREAM_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glUniform3f(uColor, ((line.rgb shr 16) and 0xFF) / 255f, ((line.rgb shr 8) and 0xFF) / 255f, (line.rgb and 0xFF) / 255f)
            GLES30.glUniform1f(uAlpha, line.alpha)
            GLES30.glDrawArrays(if (line.pairs) GLES30.GL_LINES else GLES30.GL_LINE_STRIP, 0, pts.size)
        }
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    /** 2 dp, within what this GPU can draw (many only do 1 px). */
    private val lineWidth: Float by lazy {
        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        (2f * density).coerceIn(1f, maxOf(1f, range[1]))
    }

    // ---- Helpers --------------------------------------------------------------------

    /**
     * One reused native buffer for per-frame data (markers, lines). A fresh direct buffer
     * per draw is freed only when the Java heap happens to collect, so at 60 frames a
     * second native memory climbs until the process runs out. GL thread only.
     */
    private var scratch: FloatBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun floats(a: FloatArray, n: Int): FloatBuffer {
        if (scratch.capacity() < n) {
            var cap = scratch.capacity()
            while (cap < n) cap *= 2
            scratch = ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        scratch.clear()
        scratch.put(a, 0, n).position(0)
        return scratch
    }

    private fun upload(vbo: Int, data: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data, data.size), GLES30.GL_STATIC_DRAW)
    }

    /**
     * Drops the least recently used textures above the cap, but never zoom 0–3 of the base
     * map or the bundled globe: that coarse backbone is what fills in anywhere you move.
     */
    /** Textures drawn in the current frame: never dropped, or the cache would thrash. */
    private val usedThisFrame = HashSet<SourcedTile>()

    private fun trimTextures() {
        var excess = textures.size - TEXTURE_CAP
        if (excess <= 0) return
        val it = textures.entries.iterator()
        while (excess > 0 && it.hasNext()) {
            val e = it.next()
            val k = e.key
            if (k in usedThisFrame) continue
            if (k.key.z <= 3 && (k.source === base || k.source === TileSource.BLUE_MARBLE)) continue
            GLES30.glDeleteTextures(1, intArrayOf(e.value), 0)
            it.remove()
            excess--
        }
    }

    private fun <K> trim(map: LinkedHashMap<K, Int>, cap: Int, free: (Int) -> Unit) {
        val it = map.entries.iterator()
        var excess = map.size - cap
        while (excess > 0 && it.hasNext()) {
            free(it.next().value)
            it.remove()
            excess--
        }
    }

    private fun shader(type: Int, src: String, what: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("Globe: $what shader failed to compile: ${log.trim().take(200)}")
        }
        return s
    }

    private fun program(vs: String, fs: String, what: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs, what))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs, what))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Globe: $what program failed to link: ${GLES30.glGetProgramInfoLog(p).trim().take(200)}")
        return p
    }

    companion object {
        const val MAX_ZOOM = 20
        /**
         * A tile splits when it would cover more than this many screen pixels, so 256 px
         * tiles show at 256–512 physical px: still sharper than a web map on a ~450 dpi
         * phone (which shows them at ~700). Measured on a 1220×2712 screen at 800 m up:
         * 18 tiles instead of 72 at 384 px — the main cost of close-up views.
         */
        private const val SPLIT_PX = 512.0
        private const val TILE_LIMIT = 180
        private const val TEXTURE_CAP = 300 // up to ~70 MB of GPU memory, which phones share with RAM
        private const val MESH_CAP = 400
        private const val UPLOADS_PER_FRAME = 10
        private val UV_IDENTITY = doubleArrayOf(1.0, 0.0, 0.0)

        private const val SPACE_R = 0.012f; private const val SPACE_G = 0.024f; private const val SPACE_B = 0.039f
        private const val OCEAN_R = 0.043f; private const val OCEAN_G = 0.102f; private const val OCEAN_B = 0.165f
        private const val CAP_R = 0.043f; private const val CAP_G = 0.102f; private const val CAP_B = 0.165f

        private const val TILE_VS = """#version 300 es
uniform mat4 uMvp;
uniform vec3 uUv;
uniform vec3 uCenter; // world position the vertices are relative to
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
out vec3 vNormal;
void main() {
    vUv = aUv * uUv.x + uUv.yz;
    // Normalised here, in high precision: positions are millions of metres, past what the
    // fragment shader's mediump float holds (65,504 on most phones). Unnormalised, the value
    // overflowed and the night side became a fixed half of the globe instead of following the Sun.
    vNormal = normalize(uCenter + aPos);
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""

        private const val TILE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uHasTex;
uniform vec3 uColor;
uniform float uAlpha;
uniform vec3 uSun;    // unit vector toward the Sun, world frame
uniform float uShade; // 1: darken the night side
uniform float uMode;  // 1: night lights (brightness as opacity, night side only)
uniform float uFloor; // how bright the night side stays (darker from space)
in vec2 vUv;
in vec3 vNormal;
out vec4 outColor;
void main() {
    vec4 c = uHasTex > 0.5 ? texture(uTex, vUv) : vec4(uColor, 1.0);
    // 0 on the night side, 1 in daylight, with a twilight band across the terminator.
    float day = smoothstep(-0.10, 0.08, dot(normalize(vNormal), uSun)); // re-normalised: interpolation shortens it
    if (uMode > 0.5) {
        float glow = max(c.r, max(c.g, c.b));
        outColor = vec4(c.rgb * vec3(1.0, 0.92, 0.75), glow * (1.0 - day) * uAlpha);
        return;
    }
    float light = mix(1.0, mix(uFloor, 1.0, day), uShade);
    outColor = vec4(c.rgb * light, c.a * uAlpha);
}"""

        private const val ROADS_MAX_ALT = 1_500_000.0

        /** Above this height a marker is in orbit and faces the screen. */
        private const val ORBIT_M = 50_000.0

        private const val POINT_VS = """#version 300 es
uniform mat4 uMvp;      // projection x view rotation; positions are relative to the eye
uniform vec3 uEye;      // eye in world metres (float is enough for directions)
uniform vec3 uRight;    // screen axes in the world, for things in orbit
uniform vec3 uUp;
uniform float uPx;      // 1 / focal length in pixels: world size of a pixel at unit distance
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aSize;
layout(location = 2) in vec3 aColor;
layout(location = 3) in float aRing;
layout(location = 4) in float aShape;
layout(location = 5) in float aBearing; // radians clockwise from north
layout(location = 6) in float aBill;    // 1: faces the screen (orbit), 0: lies on the ground
layout(location = 7) in vec2 aCorner;   // -1..1
out vec3 vColor;
out float vRing;
out float vSize;
flat out int vShape;
out vec2 vCorner;
void main() {
    float dist = length(aPos);
    float hs = aSize * 0.5 * dist * uPx; // half size in metres: same size in pixels at any distance
    vec3 ax;
    vec3 ay;
    vec3 lift = vec3(0.0);
    if (aBill > 0.5) {
        ax = uRight;
        ay = uUp;
    } else {
        // Tangent plane at the marker: east and north, turned to its bearing.
        vec3 n = normalize(aPos + uEye);
        vec3 east = cross(vec3(0.0, 0.0, 1.0), n);
        east = length(east) < 1e-6 ? vec3(0.0, 1.0, 0.0) : normalize(east);
        vec3 north = cross(n, east);
        float c = cos(aBearing);
        float s = sin(aBearing);
        ay = north * c + east * s;   // the shape's "up" points along its bearing
        ax = east * c - north * s;
        lift = n * dist * 0.0015;    // just above the ground, never inside it
    }
    vec3 p = aPos + lift + (ax * aCorner.x + ay * aCorner.y) * hs;
    gl_Position = uMvp * vec4(p, 1.0);
    vColor = aColor;
    vRing = aRing;
    vSize = aSize;
    vShape = int(aShape + 0.5);
    vCorner = aCorner;
}"""

        private const val POINT_FS = """#version 300 es
precision mediump float;
uniform float uOutline;
in vec3 vColor;
in float vRing;
in float vSize;
flat in int vShape;
in vec2 vCorner;
out vec4 outColor;

const vec3 DARK = vec3(0.01, 0.02, 0.04);

// Aircraft and ships: an arrow pointing along +y (its bearing), notched at the tail.
bool plane(vec2 p) {
    if (p.y < -0.6 || p.y > 0.9) return false;
    if (abs(p.x) > 0.55 * (0.9 - p.y) / 1.5) return false;
    return !(p.y < -0.2 - 0.727 * abs(p.x));
}

void main() {
    vec2 c = vCorner;
    float r = length(c) * vSize * 0.5; // distance from centre, px
    float edge = vSize * 0.5;

    if (vRing > 0.5) {
        float aa = 1.0 - smoothstep(edge - 1.0, edge, r);
        float ring = smoothstep(edge - 4.0, edge - 3.0, r);
        if (aa * ring <= 0.0) discard;
        outColor = vec4(1.0, 1.0, 1.0, ring * aa);
        return;
    }

    if (vShape == 1) {
        if (plane(c)) { outColor = vec4(vColor, 1.0); return; }
        if (plane(c * 0.84)) { outColor = vec4(DARK, 0.9); return; }
        discard;
    }

    if (vShape == 2) {
        float d = abs(c.x) + abs(c.y);
        if (d > 1.0) discard;
        outColor = d > 0.72 ? vec4(DARK, 0.9) : vec4(vColor, 1.0);
        return;
    }

    if (vShape == 3) {
        float d = length(c);
        if (d > 1.0) discard;
        if (d < 0.42) outColor = vec4(vColor, 1.0);
        else if (d < 0.58) outColor = vec4(1.0);
        else outColor = vec4(vColor, 0.22 * (1.0 - smoothstep(0.9, 1.0, d)));
        return;
    }

    float aa = 1.0 - smoothstep(edge - 1.0, edge, r);
    if (aa <= 0.0) discard;
    vec3 col = r > edge - uOutline ? DARK : vColor;
    outColor = vec4(col, aa);
}"""

        private const val GLOW_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"""

        private const val GLOW_FS = """#version 300 es
precision highp float; // pixel coordinates and radii exceed mediump range
uniform vec2 uCenter;
uniform float uRadius;
out vec4 outColor;
void main() {
    float d = distance(gl_FragCoord.xy, uCenter) / uRadius;
    float a = d < 1.0 ? 0.0 : exp(-(d - 1.0) * 16.0) * 0.55;
    outColor = vec4(0.31, 0.62, 0.95, a);
}"""
    }
}
