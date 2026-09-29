package com.verisonder.sondereye.globe

import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.TileKey
import com.verisonder.sondereye.core.TileSource
import com.verisonder.sondereye.core.V3

/** One marker. The same as on the phone. */
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
        /** A bus stop sign: a rounded square in the colour, a white bus on it. */
        const val SHAPE_BUS = 4
    }
}

/** One pass over a ribbon array: which array, how wide on screen (dp), colour, opacity. */
class RibbonPass(val index: Int, val widthDp: Float, val rgb: Int, val alpha: Float)

/**
 * Roads (or a bus route) as the phone builds them: ribbon arrays around one origin (see
 * OsmRoads.ribbons), drawn in the order of [passes]. Here they are drawn as stroked lines.
 */
class RoadSet(val origin: V3, val ribbons: Array<FloatArray>, val passes: List<RibbonPass>) {
    /** Each array's segments as world points: x0 y0 z0 x1 y1 z1 per segment. */
    val segments: Array<DoubleArray> by lazy {
        Array(ribbons.size) { i ->
            val f = ribbons[i]
            val per = 7 * 6 // six ribbon vertices of seven floats per segment
            val n = f.size / per
            DoubleArray(n * 6) { j ->
                val s = j / 6
                val k = j % 6
                // The first vertex holds this end (0..2) and the other end (3..5).
                val v = f[s * per + k].toDouble()
                v + when (k % 3) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            }
        }
    }
}

/** A polyline; with [pairs], separate segments (each two points). */
class GlobeLine(val points: List<V3>, val rgb: Int, val alpha: Float = 1f, val pairs: Boolean = false)

data class GlobeStatus(
    /** Tiles on screen still showing a blurrier parent or nothing. */
    val loading: Int,
    /** Tile downloads that failed since the last one that worked. */
    val failures: Int,
    val lastFailure: String?,
)

/** A tile of one source. */
data class SourcedTile(val source: TileSource, val key: TileKey) {
    val id get() = source.id + "/" + key.z + "/" + key.x + "/" + key.y
    override fun equals(other: Any?) = other is SourcedTile && other.source.id == source.id && other.key == key
    override fun hashCode() = source.id.hashCode() * 31 + key.hashCode()
}
