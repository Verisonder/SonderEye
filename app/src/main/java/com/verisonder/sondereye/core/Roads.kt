package com.verisonder.sondereye.core

import java.net.URLEncoder
import java.util.Locale

/**
 * Roads drawn by the app itself when close in. Esri's roads layer hides its lines at the
 * largest scales, and a stretched picture of them is blurry and carries its text along, so
 * at street level the roads come from OpenStreetMap and are drawn as crisp bands.
 */
class Road(
    /** 0 motorway … 5 service: decides width and colour. */
    val cls: Int,
    /** [lat, lon] points. */
    val pts: List<DoubleArray>,
)

object OsmRoads {
    /** The classes drawn; the index is the class. */
    private val CLASSES = listOf(
        setOf("motorway", "trunk", "motorway_link", "trunk_link"),
        setOf("primary", "primary_link"),
        setOf("secondary", "secondary_link"),
        setOf("tertiary", "tertiary_link"),
        setOf("residential", "unclassified", "living_street", "road"),
        setOf("service", "pedestrian"),
    )
    const val CLASS_COUNT = 6

    fun classOf(highway: String?): Int? = CLASSES.indexOfFirst { highway in it }.takeIf { it >= 0 }

    fun query(s: Double, w: Double, n: Double, e: Double): String {
        val box = "%.5f,%.5f,%.5f,%.5f".format(Locale.ROOT, s, w, n, e)
        val kinds = CLASSES.flatten().joinToString("|")
        return "data=" + URLEncoder.encode(
            "[out:json][timeout:40];way[\"highway\"~\"^($kinds)$\"]($box);out geom qt;", "UTF-8",
        )
    }

    fun parse(text: String): List<Road> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["remark"] as? String)?.let { if ("runtime error" in it) throw Json.ParseError(it.take(120)) }
        val els = root["elements"] as? List<*> ?: throw Json.ParseError("No elements")
        return els.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            if (m["type"] != "way") return@mapNotNull null
            val cls = classOf((m["tags"] as? Map<*, *>)?.get("highway") as? String) ?: return@mapNotNull null
            val pts = (m["geometry"] as? List<*> ?: return@mapNotNull null).mapNotNull { g ->
                val p = g as? Map<*, *> ?: return@mapNotNull null
                val lat = p["lat"] as? Double ?: return@mapNotNull null
                val lon = p["lon"] as? Double ?: return@mapNotNull null
                doubleArrayOf(lat, lon)
            }
            if (pts.size < 2) null else Road(cls, pts)
        }
    }

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
