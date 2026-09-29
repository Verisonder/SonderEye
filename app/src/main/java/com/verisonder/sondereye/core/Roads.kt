package com.verisonder.sondereye.core

/**
 * Roads drawn by the app itself when close in. Esri's roads layer hides its lines at the
 * largest scales, and a stretched picture of them is blurry and carries its text along, so
 * at street level the roads come from OpenStreetMap's data (as vector tiles, [RoadTiles])
 * and are drawn as crisp bands.
 */
class Road(
    /** 0 motorway … 5 service: decides width and colour. */
    val cls: Int,
    /** [lat, lon] points. */
    val pts: List<DoubleArray>,
)

object OsmRoads {
    /** Road classes, motorway (0) to service (5): see [RoadTiles]. */
    const val CLASS_COUNT = 6

    /** Floats per ribbon vertex: this end (3), the other end (3), side (1). */
    const val FLOATS_PER_VERTEX = 7

    /**
     * Each segment as two triangles, positions relative to [origin] (so a float holds them
     * to the millimetre), lifted [liftM] off the ground. One array per class. The vertex
     * shader pushes each vertex sideways by half the width in screen pixels, so the band
     * keeps its width at any zoom.
     */
    fun ribbons(roads: List<Road>, origin: V3, liftM: Double): Array<FloatArray> {
        val out = Array(CLASS_COUNT) { FloatArray(0) }
        for (c in 0 until CLASS_COUNT) {
            val mine = roads.filter { it.cls == c }
            val segs = mine.sumOf { it.pts.size - 1 }
            val f = FloatArray(segs * 6 * FLOATS_PER_VERTEX)
            var o = 0
            fun put(p: V3, q: V3, side: Float) {
                f[o++] = p.x.toFloat(); f[o++] = p.y.toFloat(); f[o++] = p.z.toFloat()
                f[o++] = q.x.toFloat(); f[o++] = q.y.toFloat(); f[o++] = q.z.toFloat()
                f[o++] = side
            }
            for (r in mine) {
                var prev = Geo.ecef(r.pts[0][0], r.pts[0][1], liftM) - origin
                for (i in 1 until r.pts.size) {
                    val cur = Geo.ecef(r.pts[i][0], r.pts[i][1], liftM) - origin
                    // Seen from the other end the normal flips, so its sides are swapped.
                    put(prev, cur, 1f); put(prev, cur, -1f); put(cur, prev, -1f)
                    put(prev, cur, -1f); put(cur, prev, 1f); put(cur, prev, -1f)
                    prev = cur
                }
            }
            out[c] = f
        }
        return out
    }
}
