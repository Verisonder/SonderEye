package com.verisonder.sondereye.globe

import com.verisonder.sondereye.core.Astro
import com.verisonder.sondereye.core.CameraState
import com.verisonder.sondereye.core.EARTH_R
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.TileKey
import com.verisonder.sondereye.core.TileMesh
import com.verisonder.sondereye.core.TileSelect
import com.verisonder.sondereye.core.TileSource
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.core.View
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.Point
import org.jetbrains.skia.SamplingMode
import kotlin.math.sqrt

/** Constants the app's logic reads, as on the phone. */
object GlobeRenderer {
    const val MAX_ZOOM = 20

    /** Width in dp and colour per road class (motorway first), close to Esri's own. */
    private val ROAD_STYLE = listOf(
        5.5f to 0xF2A07A, 4.5f to 0xF5B794, 4f to 0xF7C9A8, 3.5f to 0xF3DDBA, 2.6f to 0xEFE3C8, 1.6f to 0xE2D8C6,
    )
    private const val ROAD_ALPHA = 0.9f

    /** Minor roads first, so the main roads are drawn over them where they meet. */
    val ROAD_PASSES = ROAD_STYLE.indices.reversed().map { RibbonPass(it, ROAD_STYLE[it].first, ROAD_STYLE[it].second, ROAD_ALPHA) }
}

/**
 * Draws the globe onto a Skia canvas: the phone's renderer done on the CPU. Each tile's
 * mesh is projected here and drawn as textured triangles (Skia's drawTriangles), the far
 * side of the Earth left out; night, city lights and the overlays are further passes over
 * the same triangles. Roads, lines and markers are drawn by the canvas afterwards.
 */
class Painter(private val g: GlobeView) {
    private val meshes = object : LinkedHashMap<TileKey, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, FloatArray>) = size > 800
    }
    private val indexCache = HashMap<Int, ShortArray>()
    private val used = HashSet<SourcedTile>()
    private val projected = HashMap<TileKey, Projected?>()
    private var projCam: CameraState? = null
    private var projW = 0
    private var projH = 0
    private var lastStatus: GlobeStatus? = null

    private val texPaint = Paint().apply { isAntiAlias = false }
    private val shadePaint = Paint().apply { isAntiAlias = false }

    /** One tile's projected mesh for this frame. */
    private class Projected(
        val pos: Array<Point>,
        /** Skirts and surface: for the picture itself. */
        val indices: ShortArray,
        /** Surface only: for night, lights and the overlays, which must not double up at the seams. */
        val surface: ShortArray,
        val normals: FloatArray,
        val uv: FloatArray,
    )

    fun draw(c: Canvas, v: View, density: Float) {
        g.tiles.nextFrame()
        used.clear()
        val alt = v.cam.alt
        val cx = v.width / 2.0
        val cy = v.height / 2.0
        val list = TileSelect.select(v, GlobeRenderer.MAX_ZOOM, SPLIT_PX, TILE_LIMIT).sortedByDescending { k ->
            v.project(k.center())?.let { (it[0] - cx) * (it[0] - cx) + (it[1] - cy) * (it[1] - cy) } ?: Double.MAX_VALUE
        }
        // Kept while the camera stays put: the globe also redraws when markers move (every second).
        if (v.cam != projCam || v.width != projW || v.height != projH) {
            projected.clear()
            projCam = v.cam; projW = v.width; projH = v.height
        }
        fun proj(k: TileKey) = projected.getOrPut(k) { project(k, v) }

        val shading = g.dayNight
        val sun = Astro.sun(System.currentTimeMillis()).norm()
        val zoomedOut = ((alt - 60_000.0) / (1_500_000.0 - 60_000.0)).coerceIn(0.0, 1.0)
        val nightFloor = 0.75 - 0.59 * zoomedOut
        val lightsK = ((alt - 250_000.0) / (1_200_000.0 - 250_000.0)).coerceIn(0.0, 1.0)

        val base = g.base
        val overlays = g.overlays.filter {
            (!it.night || (shading && lightsK > 0)) && !(it.id == "esri-roads" && alt > ROADS_MAX_ALT)
        }
        val baseReady = HashSet<TileKey>()
        var loading = 0

        for ((pass, src) in (listOf(base) + overlays).withIndex()) {
            if (pass == 1) drawRoads(c, v, density, g.roads.takeIf { alt <= g.roadsMaxAlt })
            for (k in list) {
                if (k.z < src.minZoom || k.z > src.maxDrawZoom) continue
                val want = SourcedTile(src, src.keyFor(k))
                var found: SourcedTile? = null
                val mayRequest = pass == 0 || k in baseReady || src.night
                if (g.tiles.has(want)) {
                    found = want
                    if (pass == 0) baseReady.add(k)
                } else if (!mayRequest) {
                    found = loadedAncestor(src, want.key)
                } else {
                    val gone = want in g.tiles.absent || g.tiles.failedRecently(want)
                    if (pass == 0 && gone) baseReady.add(k)
                    if (!gone) {
                        loading++
                        g.tiles.request(want)
                        want.key.parent()?.let { p ->
                            val pt = SourcedTile(src, p)
                            if (!g.tiles.has(pt) && pt !in g.tiles.absent) g.tiles.request(pt)
                        }
                    } else if (requestNearest(src, want.key)) loading++
                    found = loadedAncestor(src, want.key)
                }
                if (pass == 0 && found?.key != want.key) {
                    // The bundled Blue Marble fills in wherever it is sharper than what has arrived.
                    val bm = TileSource.BLUE_MARBLE
                    val bmWant = SourcedTile(bm, bm.keyFor(k))
                    val bmFound = if (g.tiles.has(bmWant)) bmWant else {
                        if (bmWant !in g.tiles.absent) g.tiles.request(bmWant)
                        loadedAncestor(bm, bmWant.key)
                    }
                    if (bmFound != null && bmFound.key.z > (found?.key?.z ?: -1)) found = bmFound
                }
                if (found == null && src.transparent) continue
                val p = proj(k) ?: continue
                if (found == null) {
                    // Nothing yet: plain ocean blue.
                    shadePaint.shader = null
                    shadePaint.color = OCEAN
                    c.drawTriangles(p.pos, null, null, p.indices, BlendMode.SRC_OVER, shadePaint)
                    continue
                }
                used.add(found)
                val img = g.tiles.get(found) ?: continue
                val shader = g.tiles.shader(found) ?: continue
                val uvt = k.uvIn(found.key)
                val tex = Array(p.pos.size) { i ->
                    if (i * 2 + 1 >= p.uv.size) Point(0f, 0f)
                    else Point(
                        ((p.uv[i * 2] * uvt[0] + uvt[1]) * img.width).toFloat(),
                        ((p.uv[i * 2 + 1] * uvt[0] + uvt[2]) * img.height).toFloat(),
                    )
                }
                texPaint.shader = shader
                if (src.night) {
                    // City lights: the picture's brightness, added, on the dark side only.
                    val k2 = (src.alpha * lightsK).toFloat()
                    val colors = IntArray(p.pos.size) { i ->
                        val d = day(p.normals, i, sun)
                        argb(((1 - d) * k2 * 255).toInt(), 255, 235, 191)
                    }
                    texPaint.blendMode = BlendMode.PLUS
                    c.drawTriangles(p.pos, colors, tex, p.surface, BlendMode.MODULATE, texPaint)
                    texPaint.blendMode = BlendMode.SRC_OVER
                } else {
                    texPaint.alpha = (src.alpha * 255).toInt()
                    c.drawTriangles(p.pos, null, tex, if (pass == 0) p.indices else p.surface, BlendMode.SRC_OVER, texPaint)
                    texPaint.alpha = 255
                    if (pass == 0 && shading) {
                        // The night side: darker, with a twilight band across the terminator.
                        val colors = IntArray(p.pos.size) { i ->
                            val light = nightFloor + (1 - nightFloor) * day(p.normals, i, sun)
                            argb(((1 - light) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                        }
                        shadePaint.shader = null
                        shadePaint.color = 0xFF000000.toInt()
                        c.drawTriangles(p.pos, colors, null, p.surface, BlendMode.DST, shadePaint)
                    }
                }
                texPaint.shader = null
            }
        }
        if (overlays.isEmpty()) drawRoads(c, v, density, g.roads.takeIf { alt <= g.roadsMaxAlt })
        drawCaps(c, v)
        g.tiles.trim(used)

        val status = GlobeStatus(loading, g.tiles.failures, if (g.tiles.failures > 0) g.tiles.lastFailure else null)
        if (status != lastStatus) {
            lastStatus = status
            javax.swing.SwingUtilities.invokeLater { g.status(status) }
        }
    }

    private val capPaint = Paint().apply { isAntiAlias = true; color = OCEAN }

    /** The polar caps beyond the map's 85°: filled flat, so the globe has no holes at the poles. */
    private fun drawCaps(c: Canvas, v: View) {
        for (north in listOf(true, false)) {
            val lat = if (north) CAP_LAT else -CAP_LAT
            if (!v.aboveHorizon(Geo.ecef(if (north) 90.0 else -90.0, 0.0))) continue
            val path = Path()
            var first = true
            var any = false
            for (i in 0..72) {
                val p = Geo.ecef(lat, -180.0 + i * 5.0)
                val s = if (v.aboveHorizon(p)) v.project(p) else null
                if (s == null) continue
                if (first) path.moveTo(s[0].toFloat(), s[1].toFloat()) else path.lineTo(s[0].toFloat(), s[1].toFloat())
                first = false
                any = true
            }
            if (any) c.drawPath(path, capPaint)
            path.close()
        }
    }

    /** 0 on the night side, 1 in daylight, smooth across the terminator. */
    private fun day(n: FloatArray, i: Int, sun: V3): Double {
        val d = n[i * 3] * sun.x + n[i * 3 + 1] * sun.y + n[i * 3 + 2] * sun.z
        val t = ((d - -0.10) / (0.08 - -0.10)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Projects tile [k] into screen pixels. Triangles are kept when all three corners are in
     * front of the camera and at least one is on this side of the horizon; skirts come first,
     * so the tile's surface is drawn over them.
     */
    private fun project(k: TileKey, v: View): Projected? {
        val mesh = meshes.getOrPut(k) { TileMesh.vertices(k) }
        val n = TileMesh.segments(k.z)
        val idx = indexCache.getOrPut(n) { skirtsFirst(TileMesh.indices(n), n) }
        val center = k.center()
        val rel = center - v.eye
        val count = mesh.size / 5
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val ok = BooleanArray(count)
        val front = BooleanArray(count)
        val normals = FloatArray(count * 3)
        val uv = FloatArray(count * 2)
        val f = v.forward
        val r = v.right
        val u = v.up
        val eye = v.eye
        var anyFront = false
        for (i in 0 until count) {
            val lx = mesh[i * 5].toDouble()
            val ly = mesh[i * 5 + 1].toDouble()
            val lz = mesh[i * 5 + 2].toDouble()
            uv[i * 2] = mesh[i * 5 + 3]
            uv[i * 2 + 1] = mesh[i * 5 + 4]
            val dx = rel.x + lx
            val dy = rel.y + ly
            val dz = rel.z + lz
            val z = dx * f.x + dy * f.y + dz * f.z
            // World position, for the horizon and the Sun.
            val px = center.x + lx
            val py = center.y + ly
            val pz = center.z + lz
            val len = sqrt(px * px + py * py + pz * pz)
            val nx = px / len
            val ny = py / len
            val nz = pz / len
            normals[i * 3] = nx.toFloat(); normals[i * 3 + 1] = ny.toFloat(); normals[i * 3 + 2] = nz.toFloat()
            front[i] = (eye.x * nx + eye.y * ny + eye.z * nz) - len > -1.0
            if (front[i]) anyFront = true
            if (z <= v.near * 0.5) continue
            ok[i] = true
            xs[i] = (v.width / 2.0 + (dx * r.x + dy * r.y + dz * r.z) / z * v.focalPx).toFloat()
            ys[i] = (v.height / 2.0 - (dx * u.x + dy * u.y + dz * u.z) / z * v.focalPx).toFloat()
        }
        if (!anyFront) return null
        val keep = ShortArray(idx.size)
        val surface = ShortArray(idx.size)
        var m = 0
        var ms = 0
        val skirtEnd = idx.size - n * n * 6 // skirts come first
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t].toInt()
            val b = idx[t + 1].toInt()
            val cc = idx[t + 2].toInt()
            if (ok[a] && ok[b] && ok[cc] && (front[a] || front[b] || front[cc])) {
                keep[m++] = idx[t]; keep[m++] = idx[t + 1]; keep[m++] = idx[t + 2]
                if (t >= skirtEnd) { surface[ms++] = idx[t]; surface[ms++] = idx[t + 1]; surface[ms++] = idx[t + 2] }
            }
            t += 3
        }
        if (m == 0) return null
        // Skia wants a multiple of three positions even with indices: pad with a spare point.
        val padded = ((count + 2) / 3) * 3
        val pos = Array(padded) { i -> if (i < count) Point(xs[i], ys[i]) else Point(xs[0], ys[0]) }
        return Projected(pos, keep.copyOf(m), surface.copyOf(ms), normals, uv)
    }

    /** The phone draws skirts after the grid (the depth test hides them); here they go first. */
    private fun skirtsFirst(all: ShortArray, n: Int): ShortArray {
        val grid = n * n * 6
        return all.copyOfRange(grid, all.size) + all.copyOfRange(0, grid)
    }

    private fun loadedAncestor(src: TileSource, k: TileKey): SourcedTile? {
        var a = k.parent()
        while (a != null) {
            val t = SourcedTile(src, a)
            if (g.tiles.has(t)) return t
            a = a.parent()
        }
        return null
    }

    private fun requestNearest(src: TileSource, k: TileKey): Boolean {
        var a = k.parent()
        while (a != null) {
            val t = SourcedTile(src, a)
            if (g.tiles.has(t)) return false
            if (t !in g.tiles.absent && !g.tiles.failedRecently(t)) {
                g.tiles.request(t)
                return true
            }
            a = a.parent()
        }
        return false
    }

    private val roadPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeCap = PaintStrokeCap.ROUND
        strokeJoin = PaintStrokeJoin.ROUND
    }

    /** Roads (or a picked bus route): each class a stroked path, fixed width on screen. */
    fun drawRoads(c: Canvas, v: View, density: Float, set: RoadSet?) {
        if (set == null) return
        val segs = set.segments
        for (p in set.passes) {
            val s = segs.getOrNull(p.index) ?: continue
            if (s.isEmpty()) continue
            val path = Path()
            var n = 0
            var i = 0
            while (i + 5 < s.size) {
                val a = V3(s[i], s[i + 1], s[i + 2])
                val b = V3(s[i + 3], s[i + 4], s[i + 5])
                i += 6
                if (!v.aboveHorizon(a) && !v.aboveHorizon(b)) continue
                val pa = v.project(a) ?: continue
                val pb = v.project(b) ?: continue
                if (offscreen(pa, v) && offscreen(pb, v)) continue
                path.moveTo(pa[0].toFloat(), pa[1].toFloat())
                path.lineTo(pb[0].toFloat(), pb[1].toFloat())
                n++
            }
            if (n == 0) { path.close(); continue }
            roadPaint.color = (((p.alpha * 255).toInt() shl 24) or p.rgb)
            roadPaint.strokeWidth = p.widthDp * density
            c.drawPath(path, roadPaint)
            path.close()
        }
    }

    private fun offscreen(p: DoubleArray, v: View) =
        p[0] < -200 || p[1] < -200 || p[0] > v.width + 200 || p[1] > v.height + 200

    companion object {
        private const val SPLIT_PX = 512.0
        private const val TILE_LIMIT = 180
        private const val ROADS_MAX_ALT = 1_500_000.0
        /** Where Web Mercator tiles end. */
        private const val CAP_LAT = 85.0511
        const val OCEAN = 0xFF0B1A2A.toInt()
        const val SPACE = 0xFF03060A.toInt()
        @Suppress("unused") private val HOME = CameraState.HOME
        @Suppress("unused") private const val R = EARTH_R
    }
}
