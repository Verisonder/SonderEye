package com.verisonder.sondereye.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Everything the globe computes, in double precision and without Android, so it is
 * unit-tested off-device. The renderer only turns these results into GL calls.
 *
 * Earth is a sphere (the imagery is Web Mercator, which is spherical too).
 * World frame: x toward lat 0 / lon 0, y toward lat 0 / lon 90E, z toward the north pole.
 * Units: metres.
 */

const val EARTH_R = 6_371_008.8

data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(this dot this)
    fun norm(): V3 = len().let { if (it == 0.0) this else this * (1.0 / it) }

    companion object {
        val Z = V3(0.0, 0.0, 1.0)
    }
}

object Geo {
    const val MAX_LAT = 85.05112877980659 // edge of Web Mercator

    fun toRad(d: Double) = d * PI / 180.0
    fun toDeg(r: Double) = r * 180.0 / PI

    fun ecef(latDeg: Double, lonDeg: Double, h: Double = 0.0): V3 {
        val la = toRad(latDeg)
        val lo = toRad(lonDeg)
        val r = EARTH_R + h
        return V3(r * cos(la) * cos(lo), r * cos(la) * sin(lo), r * sin(la))
    }

    /** [lat, lon] in degrees. */
    fun latLon(v: V3): DoubleArray {
        val n = v.norm()
        return doubleArrayOf(toDeg(asin(n.z.coerceIn(-1.0, 1.0))), toDeg(atan2(n.y, n.x)))
    }

    /** Web Mercator: y 0 is the top (north) edge, 1 the bottom. */
    fun mercYToLat(y: Double): Double = toDeg(atan(sinh(PI * (1 - 2 * y))))

    fun latToMercY(lat: Double): Double {
        val la = toRad(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2
    }

    /** Longitude difference folded into -180..180. */
    fun wrapLon(d: Double): Double {
        var x = (d + 180.0) % 360.0
        if (x < 0) x += 360.0
        return x - 180.0
    }

    /** Great-circle angle between two surface directions, radians. */
    fun angle(a: V3, b: V3): Double {
        val c = (a.norm() dot b.norm()).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(c)
    }
}

/** Column-major 4×4 matrices, as GL expects. */
object M4 {
    fun perspective(fovY: Double, aspect: Double, near: Double, far: Double): DoubleArray {
        val f = 1.0 / tan(fovY / 2)
        val m = DoubleArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1.0
        m[14] = 2 * far * near / (near - far)
        return m
    }

    /** View rotation only: camera at the origin looking along [f] with [u] up. */
    fun rotation(f: V3, u: V3): DoubleArray {
        val s = (f cross u).norm()
        val up = s cross f
        return doubleArrayOf(
            s.x, up.x, -f.x, 0.0,
            s.y, up.y, -f.y, 0.0,
            s.z, up.z, -f.z, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    fun translation(t: V3): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        t.x, t.y, t.z, 1.0,
    )

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun toFloat(m: DoubleArray, out: FloatArray) {
        for (i in 0 until 16) out[i] = m[i].toFloat()
    }
}

/** Where the camera is. Immutable, so the UI thread can hand it to the GL thread safely. */
data class CameraState(
    val lat: Double,
    val lon: Double,
    /** Metres above the surface point at the centre of the screen. */
    val alt: Double,
    /** Degrees; 0 keeps north at the top of the screen. */
    val heading: Double,
) {
    fun clamped() = copy(
        lat = lat.coerceIn(-Geo.MAX_LAT, Geo.MAX_LAT),
        lon = Geo.wrapLon(lon),
        alt = alt.coerceIn(MIN_ALT, MAX_ALT),
        heading = ((heading % 360.0) + 360.0) % 360.0,
    )

    fun view(width: Int, height: Int): View = View(this, width, height)

    companion object {
        const val MIN_ALT = 800.0
        const val MAX_ALT = 40_000_000.0
        val HOME = CameraState(lat = 25.0, lon = 0.0, alt = 20_000_000.0, heading = 0.0)
    }
}

/**
 * A camera looking straight down at (lat, lon) from alt. Rendering is camera-relative:
 * objects are positioned relative to [eye] in double precision before becoming floats,
 * which keeps close-up imagery free of float jitter.
 */
class View(val cam: CameraState, val width: Int, val height: Int) {
    val fovY = Geo.toRad(45.0)
    val aspect = width.toDouble() / max(1, height)
    val normal: V3 = Geo.ecef(cam.lat, cam.lon).norm()
    val eye: V3 = normal * (EARTH_R + cam.alt)
    val forward: V3 = normal * -1.0
    val up: V3
    val right: V3
    /** Pixels per unit of tan(angle) at the screen centre. */
    val focalPx = (height / 2.0) / tan(fovY / 2)
    val near: Double
    val far: Double
    /** Projection × view rotation. Multiply by a translation relative to [eye]. */
    val projRot: DoubleArray

    init {
        var east = V3.Z cross normal
        if (east.len() < 1e-9) east = V3(0.0, 1.0, 0.0)
        east = east.norm()
        val north = normal cross east
        val h = Geo.toRad(cam.heading)
        up = (north * cos(h) + east * sin(h)).norm()
        right = (forward cross up).norm()
        val dist = EARTH_R + cam.alt
        val horizon = sqrt(dist * dist - EARTH_R * EARTH_R)
        near = max(1.0, cam.alt * 0.5)
        far = horizon + EARTH_R * 0.05 + cam.alt
        projRot = M4.mul(M4.perspective(fovY, aspect, near, far), M4.rotation(forward, up))
    }

    /** Matrix for an object whose vertices are relative to [origin]. */
    fun mvp(origin: V3): DoubleArray = M4.mul(projRot, M4.translation(origin - eye))

    /** True if [p] (on or near the surface) is on the camera's side of the horizon. */
    fun aboveHorizon(p: V3): Boolean = ((eye - p) dot p.norm()) > -1.0

    /** Screen position in pixels (origin top-left), or null if behind the camera. */
    fun project(p: V3): DoubleArray? {
        val d = p - eye
        val z = d dot forward
        if (z <= 0) return null
        val x = (d dot right) / z
        val y = (d dot up) / z
        return doubleArrayOf(width / 2.0 + x * focalPx, height / 2.0 - y * focalPx)
    }

    fun ray(px: Double, py: Double): V3 {
        val x = (px - width / 2.0) / focalPx
        val y = (height / 2.0 - py) / focalPx
        return (forward + right * x + up * y).norm()
    }

    /** Surface point under a screen pixel, or null if the pixel shows space. */
    fun pick(px: Double, py: Double): V3? {
        val d = ray(px, py)
        val b = eye dot d
        val c = (eye dot eye) - EARTH_R * EARTH_R
        val disc = b * b - c
        if (disc < 0) return null
        val t = -b - sqrt(disc)
        if (t < 0) return null
        return eye + d * t
    }

    /** Apparent radius of the Earth on screen, pixels. */
    fun earthRadiusPx(): Double {
        val d = eye.len()
        return focalPx * tan(asin((EARTH_R / d).coerceAtMost(1.0)))
    }
}

data class TileKey(val z: Int, val x: Int, val y: Int) {
    private val n get() = 1 shl z
    val lonW get() = x.toDouble() / n * 360.0 - 180.0
    val lonE get() = (x + 1).toDouble() / n * 360.0 - 180.0
    val latN get() = Geo.mercYToLat(y.toDouble() / n)
    val latS get() = Geo.mercYToLat((y + 1).toDouble() / n)

    fun parent(): TileKey? = if (z == 0) null else TileKey(z - 1, x / 2, y / 2)

    fun children() = listOf(
        TileKey(z + 1, 2 * x, 2 * y), TileKey(z + 1, 2 * x + 1, 2 * y),
        TileKey(z + 1, 2 * x, 2 * y + 1), TileKey(z + 1, 2 * x + 1, 2 * y + 1),
    )

    /** Mercator centre, so the centre sits in the middle of the tile image. */
    fun center(): V3 {
        val my = (y + 0.5) / n
        return Geo.ecef(Geo.mercYToLat(my), (lonW + lonE) / 2)
    }

    /** Scale and offset that map this tile's 0..1 texture space into [ancestor]'s image. */
    fun uvIn(ancestor: TileKey): DoubleArray {
        val dz = z - ancestor.z
        val s = 1.0 / (1 shl dz)
        return doubleArrayOf(s, (x - (ancestor.x shl dz)) * s, (y - (ancestor.y shl dz)) * s)
    }

    fun url() = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
}

object TileSelect {
    const val ROOT_Z = 2

    /**
     * Tiles to draw for this view: split until a tile would show at no more than
     * [splitPx] screen pixels, stop at [maxZoom], never more than [limit] tiles.
     * Breadth-first, so the limit trims the finest detail, never whole regions.
     */
    fun select(view: View, maxZoom: Int, splitPx: Double, limit: Int = 180): List<TileKey> {
        val screenGround = screenGround(view)
        val queue = ArrayDeque<TileKey>()
        val n = 1 shl ROOT_Z
        for (y in 0 until n) for (x in 0 until n) queue.add(TileKey(ROOT_Z, x, y))
        val out = ArrayList<TileKey>()
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            val nearest = nearestPoint(k, view.cam)
            if (!visible(view, k, nearest, screenGround)) continue
            val room = out.size + queue.size + 4 <= limit
            if (k.z < maxZoom && room && screenSize(view, k, nearest) > splitPx) queue.addAll(k.children())
            else out.add(k)
        }
        return out
    }

    /** Surface points under the screen's centre, corners and edge midpoints. */
    private fun screenGround(view: View): List<DoubleArray> {
        val w = view.width.toDouble()
        val h = view.height.toDouble()
        val pts = ArrayList<DoubleArray>()
        for (fx in doubleArrayOf(0.0, 0.5, 1.0)) for (fy in doubleArrayOf(0.0, 0.5, 1.0)) {
            view.pick(fx * w, fy * h)?.let { pts.add(Geo.latLon(it)) }
        }
        return pts
    }

    private fun contains(k: TileKey, ll: DoubleArray): Boolean =
        ll[0] <= k.latN && ll[0] >= k.latS && ll[1] >= k.lonW && ll[1] <= k.lonE

    /** The tile's point closest to the spot under the camera. */
    fun nearestPoint(k: TileKey, cam: CameraState): V3 {
        val lat = cam.lat.coerceIn(k.latS, k.latN)
        val cLon = (k.lonW + k.lonE) / 2
        val half = (k.lonE - k.lonW) / 2
        val lon = cLon + Geo.wrapLon(cam.lon - cLon).coerceIn(-half, half)
        return Geo.ecef(lat, lon)
    }

    private fun visible(view: View, k: TileKey, nearest: V3, screenGround: List<DoubleArray>): Boolean {
        if (screenGround.any { contains(k, it) }) return true
        var anyAbove = false
        var left = true; var rightSide = true; var top = true; var bottom = true
        fun test(p: V3) {
            if (!view.aboveHorizon(p)) return
            val s = view.project(p) ?: return
            anyAbove = true
            if (s[0] >= 0) left = false
            if (s[0] <= view.width) rightSide = false
            if (s[1] >= 0) top = false
            if (s[1] <= view.height) bottom = false
        }
        test(nearest)
        val steps = 4
        for (j in 0..steps) {
            val my = (k.y + j.toDouble() / steps) / (1 shl k.z)
            val lat = Geo.mercYToLat(my)
            for (i in 0..steps) test(Geo.ecef(lat, k.lonW + (k.lonE - k.lonW) * i / steps))
        }
        return anyAbove && !(left || rightSide || top || bottom)
    }

    private fun screenSize(view: View, k: TileKey, nearest: V3): Double {
        val lat = Geo.toRad(Geo.latLon(nearest)[0])
        val width = EARTH_R * Geo.toRad(k.lonE - k.lonW) * cos(lat)
        val height = EARTH_R * Geo.toRad(k.latN - k.latS)
        val dist = max(1.0, (nearest - view.eye).len())
        return max(width, height) / dist * view.focalPx
    }
}

/** Grid mesh for one tile, positions relative to [TileKey.center], with skirts. */
object TileMesh {
    /** Segments per side: coarse tiles cover a lot of curvature. */
    fun segments(z: Int) = if (z <= 3) 32 else 16

    /** Interleaved x, y, z, u, v. */
    fun vertices(k: TileKey): FloatArray {
        val n = segments(k.z)
        val c = k.center()
        val tileM = EARTH_R * Geo.toRad(k.lonE - k.lonW) * cos(Geo.toRad((k.latN + k.latS) / 2))
        val skirt = max(50.0, tileM * 0.04)
        val out = FloatArray(((n + 1) * (n + 1) + 4 * (n + 1)) * 5)
        var o = 0
        fun put(lat: Double, lon: Double, h: Double, u: Double, v: Double) {
            val p = Geo.ecef(lat, lon, h) - c
            out[o++] = p.x.toFloat(); out[o++] = p.y.toFloat(); out[o++] = p.z.toFloat()
            out[o++] = u.toFloat(); out[o++] = v.toFloat()
        }
        val lats = DoubleArray(n + 1) { j -> Geo.mercYToLat((k.y + j.toDouble() / n) / (1 shl k.z)) }
        val lon = { i: Int -> k.lonW + (k.lonE - k.lonW) * i / n }
        for (j in 0..n) for (i in 0..n) put(lats[j], lon(i), 0.0, i.toDouble() / n, j.toDouble() / n)
        // Skirts hang below each edge and hide cracks between tiles of different detail.
        for (i in 0..n) put(lats[0], lon(i), -skirt, i.toDouble() / n, 0.0)
        for (i in 0..n) put(lats[n], lon(i), -skirt, i.toDouble() / n, 1.0)
        for (j in 0..n) put(lats[j], lon(0), -skirt, 0.0, j.toDouble() / n)
        for (j in 0..n) put(lats[j], lon(n), -skirt, 1.0, j.toDouble() / n)
        return out
    }

    fun indices(n: Int): ShortArray {
        val grid = { i: Int, j: Int -> j * (n + 1) + i }
        val base = (n + 1) * (n + 1)
        val top = { i: Int -> base + i }
        val bottom = { i: Int -> base + (n + 1) + i }
        val left = { j: Int -> base + 2 * (n + 1) + j }
        val right = { j: Int -> base + 3 * (n + 1) + j }
        val out = ArrayList<Int>(n * n * 6 + 4 * n * 6)
        fun quad(a: Int, b: Int, c: Int, d: Int) { out.add(a); out.add(b); out.add(c); out.add(b); out.add(d); out.add(c) }
        for (j in 0 until n) for (i in 0 until n) quad(grid(i, j), grid(i + 1, j), grid(i, j + 1), grid(i + 1, j + 1))
        for (i in 0 until n) {
            quad(grid(i, 0), grid(i + 1, 0), top(i), top(i + 1))
            quad(grid(i, n), grid(i + 1, n), bottom(i), bottom(i + 1))
            quad(grid(0, i), grid(0, i + 1), left(i), left(i + 1))
            quad(grid(n, i), grid(n, i + 1), right(i), right(i + 1))
        }
        return ShortArray(out.size) { out[it].toShort() }
    }
}

object Pick {
    /** Nearest visible point within [reachPx] of the tap, as an index into [latLon] pairs. */
    fun nearest(
        view: View, lat: DoubleArray, lon: DoubleArray, px: Double, py: Double, reachPx: Double,
        alt: DoubleArray? = null,
    ): Int {
        var best = -1
        var bestD = reachPx * reachPx
        // From the end: later markers are drawn on top, so they win ties.
        for (i in lat.indices.reversed()) {
            val p = Geo.ecef(lat[i], lon[i], alt?.getOrNull(i) ?: 0.0)
            if (!view.aboveHorizon(p)) continue
            val s = view.project(p) ?: continue
            val dx = s[0] - px
            val dy = s[1] - py
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}

/** Camera fly-to: shortest way round, altitude eased in log space. */
object Fly {
    fun ease(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

    fun lerp(a: CameraState, b: CameraState, t: Double): CameraState {
        val e = ease(t.coerceIn(0.0, 1.0))
        val dLon = Geo.wrapLon(b.lon - a.lon)
        val dHeading = Geo.wrapLon(b.heading - a.heading)
        val la = ln(a.alt)
        val lb = ln(b.alt)
        // Rise mid-flight when travelling far, so the move reads as a flight, not a slide.
        val arc = Geo.toRad(max(abs(b.lat - a.lat), abs(dLon))) * EARTH_R
        val peak = ln(max(max(a.alt, b.alt), min(arc * 0.8, 15_000_000.0)))
        val baseAlt = la + (lb - la) * e
        val bump = (peak - max(la, lb)).coerceAtLeast(0.0) * sin(PI * e)
        return CameraState(
            lat = a.lat + (b.lat - a.lat) * e,
            lon = a.lon + dLon * e,
            alt = exp(baseAlt + bump),
            heading = a.heading + dHeading * e,
        ).clamped()
    }
}
