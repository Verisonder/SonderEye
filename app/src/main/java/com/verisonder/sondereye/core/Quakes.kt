package com.verisonder.sondereye.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class Quake(
    val id: String,
    /** Null when USGS has not assigned one yet. */
    val mag: Double?,
    val place: String,
    /** "earthquake", "quarry blast", "explosion"… The feed mixes them. */
    val type: String,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val depthKm: Double,
    val url: String,
    val tsunami: Boolean,
)

/** USGS summary feeds. Each combination of these two is its own public URL; no key needed. */
enum class MinMag(val slug: String, val label: String) {
    ALL("all", "All"),
    M1("1.0", "1.0+"),
    M25("2.5", "2.5+"),
    M45("4.5", "4.5+"),
    SIGNIFICANT("significant", "Significant"),
}

enum class Period(val slug: String, val label: String) {
    HOUR("hour", "Past hour"),
    DAY("day", "Past day"),
    WEEK("week", "Past week"),
    MONTH("month", "Past month"),
}

object Usgs {

    fun feedUrl(min: MinMag, period: Period): String =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/${min.slug}_${period.slug}.geojson"

    class Result(val quakes: List<Quake>, val skipped: Int)

    /**
     * Parses a summary feed. Throws [Json.ParseError] if the text is not a feature
     * collection at all; single malformed features are skipped and counted, so one bad
     * entry never blanks the whole layer.
     */
    fun parse(text: String): Result {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        if (root["type"] != "FeatureCollection") throw Json.ParseError("Not a feature collection")
        val features = root["features"] as? List<*> ?: throw Json.ParseError("No features list")
        val out = ArrayList<Quake>(features.size)
        var skipped = 0
        for (f in features) {
            val q = feature(f)
            if (q == null) skipped++ else out.add(q)
        }
        // Newest first: the order the list and the card navigation read in.
        out.sortByDescending { it.timeMs }
        return Result(out, skipped)
    }

    private fun feature(f: Any?): Quake? {
        val m = f as? Map<*, *> ?: return null
        val id = m["id"] as? String ?: return null
        val p = m["properties"] as? Map<*, *> ?: return null
        val c = (m["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*> ?: return null
        val lon = c.getOrNull(0) as? Double ?: return null
        val lat = c.getOrNull(1) as? Double ?: return null
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return Quake(
            id = id,
            mag = p["mag"] as? Double,
            place = (p["place"] as? String)?.takeIf { it.isNotBlank() } ?: "Unnamed location",
            type = (p["type"] as? String) ?: "earthquake",
            timeMs = (p["time"] as? Double)?.toLong() ?: return null,
            lat = lat,
            lon = lon,
            depthKm = c.getOrNull(2) as? Double ?: 0.0,
            url = (p["url"] as? String) ?: "",
            tsunami = (p["tsunami"] as? Double) == 1.0,
        )
    }
}

object Fmt {
    fun mag(m: Double?): String = if (m == null) "M ?" else "M " + ((m * 10).roundToInt() / 10.0)

    fun ago(thenMs: Long, nowMs: Long): String {
        val s = (nowMs - thenMs) / 1000
        return when {
            s < 60 -> "Just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    fun depth(km: Double): String = "${km.roundToInt()} km deep"

    /** "quarry blast" → "Quarry blast". Plain earthquakes are the default and get no label. */
    fun typeLabel(type: String): String? =
        if (type == "earthquake") null else type.replaceFirstChar { it.uppercase() }
}
