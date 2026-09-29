package com.verisonder.sondereye.globe

import com.verisonder.sondereye.core.View
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path

/**
 * Roads (or a picked bus route): each class one stroked path, a fixed width on screen.
 * Tens of thousands of segments, so: projected without allocating, segments off screen or on
 * the far side skipped, segments that continue one another joined into one line (fewer
 * ends to round off), and the paths kept while the camera stays put.
 */
class RoadDrawer {
    private val paint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeCap = PaintStrokeCap.ROUND
        strokeJoin = PaintStrokeJoin.ROUND
    }
    private var paths = arrayOfNulls<Path>(0)
    private var keptFor: RoadSet? = null
    private var keptCam: com.verisonder.sondereye.core.CameraState? = null
    private var keptW = 0
    private var keptH = 0

    private var sx = 0f
    private var sy = 0f

    fun draw(c: Canvas, v: View, density: Float, set: RoadSet) {
        val segs = set.segments
        if (set !== keptFor || v.cam != keptCam || v.width != keptW || v.height != keptH) {
            if (paths.size != segs.size) {
                paths.forEach { it?.close() }
                paths = Array(segs.size) { Path() }
            }
            for (i in segs.indices) build(paths[i]!!, segs[i], v)
            keptFor = set; keptCam = v.cam; keptW = v.width; keptH = v.height
        }
        for (p in set.passes) {
            val path = paths.getOrNull(p.index) ?: continue
            if (path.isEmpty) continue
            paint.color = (((p.alpha * 255).toInt() shl 24) or p.rgb)
            paint.strokeWidth = p.widthDp * density
            c.drawPath(path, paint)
        }
    }

    private fun build(path: Path, s: DoubleArray, v: View) {
        path.reset()
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        var lastX = Float.NaN
        var lastY = Float.NaN
        var drawnX = 0f
        var drawnY = 0f
        var i = 0
        while (i + 5 < s.size) {
            val ok = project(v, s[i], s[i + 1], s[i + 2])
            val ax = sx
            val ay = sy
            val okB = project(v, s[i + 3], s[i + 4], s[i + 5])
            i += 6
            if (!ok || !okB) continue
            if (off(ax, ay, w, h) && off(sx, sy, w, h)) continue
            // A segment starting where the last one ended continues that line; points closer
            // than a pixel and a half to the last one drawn add nothing visible and are left out.
            if (ax != lastX || ay != lastY) {
                path.moveTo(ax, ay)
                drawnX = ax; drawnY = ay
            }
            lastX = sx
            lastY = sy
            val ddx = sx - drawnX
            val ddy = sy - drawnY
            if (ddx * ddx + ddy * ddy < 2.25f) continue
            path.lineTo(sx, sy)
            drawnX = sx; drawnY = sy
        }
    }

    private fun off(x: Float, y: Float, w: Float, h: Float) = x < -100f || y < -100f || x > w + 100f || y > h + 100f

    /** Screen position of world point (x, y, z) into [sx], [sy]; false when on the far side or behind. */
    private fun project(v: View, x: Double, y: Double, z: Double): Boolean {
        val e = v.eye
        // On or near the ground: seen when p·eye > |p|² (on this side of the horizon).
        if (x * e.x + y * e.y + z * e.z <= x * x + y * y + z * z) return false
        val dx = x - e.x
        val dy = y - e.y
        val dz = z - e.z
        val f = v.forward
        val d = dx * f.x + dy * f.y + dz * f.z
        if (d <= 0) return false
        val r = v.right
        val u = v.up
        sx = (v.width / 2.0 + (dx * r.x + dy * r.y + dz * r.z) / d * v.focalPx).toFloat()
        sy = (v.height / 2.0 - (dx * u.x + dy * u.y + dz * u.z) / d * v.focalPx).toFloat()
        return true
    }
}
