package com.verisonder.sondereye.globe

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.verisonder.sondereye.core.CameraState
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.M4
import com.verisonder.sondereye.core.TileKey
import com.verisonder.sondereye.core.TileMesh
import com.verisonder.sondereye.core.TileSelect
import com.verisonder.sondereye.core.V3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One marker, prepared on the UI thread. */
class Marker(val lat: Double, val lon: Double, val sizePx: Float, val rgb: Int) {
    val pos: V3 = Geo.ecef(lat, lon)
}

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
    private val camera: () -> CameraState,
    private val density: Float,
    private val requestRender: () -> Unit,
    private val onStatus: (GlobeStatus) -> Unit,
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var markers: List<Marker> = emptyList()
    /** Index into [markers], or -1. */
    @Volatile var selected: Int = -1

    private val uploads = ConcurrentLinkedQueue<Pair<TileKey, Bitmap>>()
    @Volatile private var failures = 0
    @Volatile private var lastFailure: String? = null

    private val loader = TileLoader(
        onLoaded = { k, b ->
            uploads.add(k to b)
            failures = 0
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
    private val textures = LinkedHashMap<TileKey, Int>(64, 0.75f, true)
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
        indexBuffers.clear()
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

    fun shutdown() = loader.shutdown()

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

        val tiles = TileSelect.select(view, MAX_ZOOM, SPLIT_PX, TILE_LIMIT)
        var loading = 0
        GLES30.glUseProgram(tileProg)
        val uMvp = GLES30.glGetUniformLocation(tileProg, "uMvp")
        val uUv = GLES30.glGetUniformLocation(tileProg, "uUv")
        val uHasTex = GLES30.glGetUniformLocation(tileProg, "uHasTex")
        val uColor = GLES30.glGetUniformLocation(tileProg, "uColor")
        GLES30.glUniform1i(GLES30.glGetUniformLocation(tileProg, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        for (k in tiles) {
            var tex = textures[k]
            var uv = UV_IDENTITY
            if (tex == null) {
                loading++
                loader.request(k)
                // Meanwhile show the closest ancestor that has arrived, stretched.
                var a = k.parent()
                while (a != null) {
                    val t = textures[a]
                    if (t != null) { tex = t; uv = k.uvIn(a); break }
                    a = a.parent()
                }
                rootOf(k)?.let { if (textures[it] == null) loader.request(it) }
            }
            M4.toFloat(view.mvp(k.center()), mvp)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniform3f(uUv, uv[0].toFloat(), uv[1].toFloat(), uv[2].toFloat())
            if (tex != null) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glUniform1f(uHasTex, 1f)
            } else {
                GLES30.glUniform1f(uHasTex, 0f)
                GLES30.glUniform3f(uColor, OCEAN_R, OCEAN_G, OCEAN_B)
            }
            drawMesh(mesh(k), TileMesh.segments(k.z))
        }

        drawCaps(view, uMvp, uHasTex, uColor)
        drawMarkers(view)
        trim(textures, TEXTURE_CAP) { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        trim(meshes, MESH_CAP) { GLES30.glDeleteBuffers(1, intArrayOf(it), 0) }

        if (uploads.isNotEmpty()) requestRender()
        val status = GlobeStatus(loading, failures, if (failures > 0) lastFailure else null)
        if (status != lastStatus) {
            lastStatus = status
            onStatus(status)
        }
    }

    private fun rootOf(k: TileKey): TileKey? {
        var a: TileKey? = k
        while (a != null && a.z > TileSelect.ROOT_Z) a = a.parent()
        return if (a == k) null else a
    }

    private fun uploadPending() {
        var n = 0
        while (n < UPLOADS_PER_FRAME) {
            val (k, bmp) = uploads.poll() ?: break
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
            if (k.z == TileSelect.ROOT_Z) sampleCapColour(k, bmp)
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

    private fun drawCaps(view: com.verisonder.sondereye.core.View, uMvp: Int, uHasTex: Int, uColor: Int) {
        GLES30.glUniform1f(uHasTex, 0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, capVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        for ((i, sign) in intArrayOf(1, -1).withIndex()) {
            val c = capRgb[i]
            GLES30.glUniform3f(uColor, c[0], c[1], c[2])
            M4.toFloat(view.mvp(Geo.ecef(90.0 * sign, 0.0)), mvp)
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

    private fun drawMarkers(view: com.verisonder.sondereye.core.View) {
        val list = markers
        val sel = selected
        val count = list.size + if (sel in list.indices) 1 else 0
        if (count == 0) return
        val stride = 8 // x y z size r g b ring
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
        }
        for (m in list) put(m, false)
        if (sel in list.indices) put(list[sel], true)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(pointProg)
        M4.toFloat(view.projRot, mvp)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pointProg, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProg, "uOutline"), 1.2f * density)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * stride * 4, floats(a, count * stride), GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride * 4, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride * 4, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride * 4, 28)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
        GLES30.glDepthMask(true)
    }

    // ---- Helpers --------------------------------------------------------------------

    private fun floats(a: FloatArray, n: Int): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a, 0, n).position(0)
        return fb
    }

    private fun upload(vbo: Int, data: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data, data.size), GLES30.GL_STATIC_DRAW)
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
        const val MAX_ZOOM = 18
        private const val SPLIT_PX = 384.0 // 256 px images shown at no more than 1.5×
        private const val TILE_LIMIT = 180
        private const val TEXTURE_CAP = 260
        private const val MESH_CAP = 400
        private const val UPLOADS_PER_FRAME = 6
        private val UV_IDENTITY = doubleArrayOf(1.0, 0.0, 0.0)

        private const val SPACE_R = 0.012f; private const val SPACE_G = 0.024f; private const val SPACE_B = 0.039f
        private const val OCEAN_R = 0.043f; private const val OCEAN_G = 0.102f; private const val OCEAN_B = 0.165f
        private const val CAP_R = 0.043f; private const val CAP_G = 0.102f; private const val CAP_B = 0.165f

        private const val TILE_VS = """#version 300 es
uniform mat4 uMvp;
uniform vec3 uUv;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv * uUv.x + uUv.yz;
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""

        private const val TILE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uHasTex;
uniform vec3 uColor;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = uHasTex > 0.5 ? vec4(texture(uTex, vUv).rgb, 1.0) : vec4(uColor, 1.0);
}"""

        private const val POINT_VS = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aSize;
layout(location = 2) in vec3 aColor;
layout(location = 3) in float aRing;
out vec3 vColor;
out float vRing;
out float vSize;
void main() {
    // Pulled 0.2% toward the eye so a marker sits on top of the surface it marks,
    // while the far side of the planet still hides it.
    gl_Position = uMvp * vec4(aPos * 0.998, 1.0);
    gl_PointSize = aSize;
    vColor = aColor;
    vRing = aRing;
    vSize = aSize;
}"""

        private const val POINT_FS = """#version 300 es
precision mediump float;
uniform float uOutline;
in vec3 vColor;
in float vRing;
in float vSize;
out vec4 outColor;
void main() {
    float r = length(gl_PointCoord * 2.0 - 1.0) * vSize * 0.5; // distance from centre, px
    float edge = vSize * 0.5;
    float aa = 1.0 - smoothstep(edge - 1.0, edge, r);
    if (aa <= 0.0) discard;
    if (vRing > 0.5) {
        float ring = smoothstep(edge - 4.0, edge - 3.0, r);
        outColor = vec4(1.0, 1.0, 1.0, ring * aa);
    } else {
        vec3 c = r > edge - uOutline ? vec3(0.01, 0.02, 0.04) : vColor;
        outColor = vec4(c, aa);
    }
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
