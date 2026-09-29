package com.verisonder.sondereye.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Roads from vector tiles in the OpenMapTiles layout (OpenFreeMap: no key, no limits, served
 * from a CDN). Each tile is a protocol buffer; only the "transportation" layer is read.
 */
object RoadTiles {
    /** The deepest zoom the tiles have; closer views use these, drawn sharp by the app. */
    const val ZOOM = 14

    fun url(x: Int, y: Int) = "https://tiles.openfreemap.org/planet/latest/$ZOOM/$x/$y.pbf"

    /** OpenMapTiles road classes to [OsmRoads] classes; others (rail, paths, ferries) are left out. */
    private val CLASS = mapOf(
        "motorway" to 0, "trunk" to 0,
        "primary" to 1,
        "secondary" to 2,
        "tertiary" to 3,
        "minor" to 4,
        "service" to 5, "busway" to 5,
    )

    /** Tiles covering the box, as (x, y) at [ZOOM]. */
    fun tilesFor(s: Double, w: Double, n: Double, e: Double): List<Pair<Int, Int>> {
        val n2 = 1 shl ZOOM
        fun tx(lon: Double) = ((lon + 180.0) / 360.0 * n2).toInt().coerceIn(0, n2 - 1)
        fun ty(lat: Double): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return ((1.0 - kotlin.math.ln(kotlin.math.tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n2).toInt().coerceIn(0, n2 - 1)
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (x in tx(w)..tx(e)) for (y in ty(n)..ty(s)) out.add(x to y)
        return out
    }

    /** Roads in one tile ([x], [y] at [ZOOM]); bytes may be gzip-compressed. */
    fun roads(raw: ByteArray, x: Int, y: Int): List<Road> {
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else raw
        val out = ArrayList<Road>()
        val r = Pb(bytes, 0, bytes.size)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 3 && tag and 7 == 2) layer(r.sub(), x, y, out) else r.skip(tag and 7)
        }
        return out
    }

    private fun layer(r: Pb, tx: Int, ty: Int, out: MutableList<Road>) {
        var name: String? = null
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (r.more()) {
            val tag = r.varint().toInt()
            when {
                tag ushr 3 == 1 && tag and 7 == 2 -> name = r.string()
                tag ushr 3 == 2 && tag and 7 == 2 -> features.add(r.sub())
                tag ushr 3 == 3 && tag and 7 == 2 -> keys.add(r.string())
                tag ushr 3 == 4 && tag and 7 == 2 -> values.add(value(r.sub()))
                tag ushr 3 == 5 && tag and 7 == 0 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (name != "transportation") return
        val classKey = keys.indexOf("class")
        if (classKey < 0) return
        for (f in features) feature(f, classKey, values, extent, tx, ty, out)
    }

    private fun value(r: Pb): String? {
        var s: String? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 1 && tag and 7 == 2) s = r.string() else r.skip(tag and 7)
        }
        return s
    }

    private fun feature(r: Pb, classKey: Int, values: List<String?>, extent: Int, tx: Int, ty: Int, out: MutableList<Road>) {
        var tags: IntArray? = null
        var type = 0
        var geom: IntArray? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            when {
                tag ushr 3 == 2 && tag and 7 == 2 -> tags = r.packed()
                tag ushr 3 == 3 && tag and 7 == 0 -> type = r.varint().toInt()
                tag ushr 3 == 4 && tag and 7 == 2 -> geom = r.packed()
                else -> r.skip(tag and 7)
            }
        }
        if (type != 2) return // lines only
        val t = tags ?: return
        var cls: Int? = null
        var i = 0
        while (i + 1 < t.size) {
            if (t[i] == classKey) cls = CLASS[values.getOrNull(t[i + 1])]
            i += 2
        }
        val c = cls ?: return
        val g = geom ?: return
        // Geometry commands: MoveTo starts a line, LineTo continues it; positions are zigzag deltas.
        val scale = 1.0 / extent
        val n = (1 shl ZOOM).toDouble()
        fun point(px: Int, py: Int): DoubleArray {
            val lon = (tx + px * scale) / n * 360.0 - 180.0
            val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (ty + py * scale) / n))))
            return doubleArrayOf(lat, lon)
        }
        var cx = 0
        var cy = 0
        var k = 0
        var line = ArrayList<DoubleArray>()
        while (k < g.size) {
            val cmd = g[k] and 7
            val count = g[k] ushr 3
            k++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (k + 1 >= g.size) return
                    cx += zigzag(g[k]); cy += zigzag(g[k + 1]); k += 2
                    if (cmd == 1) {
                        if (line.size >= 2) out.add(Road(c, line))
                        line = ArrayList()
                    }
                    line.add(point(cx, cy))
                }
                7 -> {}
                else -> return
            }
        }
        if (line.size >= 2) out.add(Road(c, line))
    }

    private fun zigzag(v: Int) = (v ushr 1) xor -(v and 1)

    /** A protocol buffer reader over part of a byte array. */
    private class Pb(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                if (p >= end) throw Json.ParseError("Truncated tile")
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
                if (shift > 63) throw Json.ParseError("Bad number in tile")
            }
        }
        fun sub(): Pb {
            val n = varint().toInt()
            if (n < 0 || p + n > end) throw Json.ParseError("Truncated tile")
            val r = Pb(b, p, p + n)
            p += n
            return r
        }
        fun string() = sub().let { String(b, it.p, it.end - it.p, Charsets.UTF_8) }
        fun packed(): IntArray {
            val s = sub()
            val out = ArrayList<Int>()
            while (s.more()) out.add(s.varint().toInt())
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> sub()
                5 -> p += 4
                else -> throw Json.ParseError("Not a vector tile")
            }
            if (p > end) throw Json.ParseError("Truncated tile")
        }
    }
}
