package com.verisonder.sondereye.core

import kotlin.math.roundToInt

data class Flight(
    /** ICAO 24-bit address, lower case hex. The stable id. */
    val hex: String,
    val callsign: String?,
    val registration: String?,
    val type: String?,
    val lat: Double,
    val lon: Double,
    /** Barometric altitude in feet; null when unknown. */
    val altFt: Int?,
    val onGround: Boolean,
    val speedKt: Double?,
    /** Degrees clockwise from true north; null when unknown. */
    val track: Double?,
)

/** Live aircraft from adsb.lol: community-fed, no key, ADSBExchange v2 format. */
object Adsb {
    /** The API caps the radius at 250 nautical miles. */
    const val MAX_NM = 250

    fun url(lat: Double, lon: Double, nm: Int): String =
        "https://api.adsb.lol/v2/lat/${"%.4f".format(java.util.Locale.ROOT, lat)}" +
            "/lon/${"%.4f".format(java.util.Locale.ROOT, lon)}/dist/${nm.coerceIn(1, MAX_NM)}"

    class Result(val flights: List<Flight>, val skipped: Int)

    fun parse(text: String): Result {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val ac = root["ac"] as? List<*> ?: throw Json.ParseError("No aircraft list")
        val out = ArrayList<Flight>(ac.size)
        var skipped = 0
        for (a in ac) {
            val m = a as? Map<*, *>
            val hex = (m?.get("hex") as? String)?.trim()?.lowercase()
            val lat = m?.get("lat") as? Double
            val lon = m?.get("lon") as? Double
            if (m == null || hex.isNullOrEmpty() || lat == null || lon == null) { skipped++; continue }
            val alt = m["alt_baro"]
            out.add(
                Flight(
                    hex = hex,
                    callsign = (m["flight"] as? String)?.trim()?.ifEmpty { null },
                    registration = (m["r"] as? String)?.trim()?.ifEmpty { null },
                    type = (m["t"] as? String)?.trim()?.ifEmpty { null },
                    lat = lat,
                    lon = lon,
                    altFt = (alt as? Double)?.roundToInt(),
                    onGround = alt == "ground",
                    speedKt = m["gs"] as? Double,
                    track = (m["track"] as? Double) ?: (m["true_heading"] as? Double),
                )
            )
        }
        return Result(out, skipped)
    }
}

data class NatEvent(
    val id: String,
    val title: String,
    /** EONET category id: wildfires, volcanoes, severeStorms, seaLakeIce, floods… */
    val category: String,
    val categoryTitle: String,
    val lat: Double,
    val lon: Double,
    val timeMs: Long?,
    val url: String?,
)

/** Open natural events from NASA EONET: wildfires, volcanoes, storms, ice. No key. */
object Eonet {
    const val URL = "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30"

    class Result(val events: List<NatEvent>, val skipped: Int)

    fun parse(text: String): Result {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val list = root["events"] as? List<*> ?: throw Json.ParseError("No events list")
        val out = ArrayList<NatEvent>()
        var skipped = 0
        for (e in list) {
            val m = e as? Map<*, *>
            val id = m?.get("id") as? String
            val cat = ((m?.get("categories") as? List<*>)?.firstOrNull() as? Map<*, *>)
            // An event can move (a storm); its latest geometry is where it is now.
            val geom = (m?.get("geometry") as? List<*>)?.lastOrNull() as? Map<*, *>
            val pos = geom?.let { position(it) }
            if (m == null || id == null || cat == null || pos == null) { skipped++; continue }
            out.add(
                NatEvent(
                    id = id,
                    title = (m["title"] as? String)?.trim() ?: "Event",
                    category = (cat["id"] as? String) ?: "other",
                    categoryTitle = (cat["title"] as? String) ?: "Event",
                    lat = pos[0],
                    lon = pos[1],
                    timeMs = (geom["date"] as? String)?.let { isoMs(it) },
                    url = ((m["sources"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("url") as? String,
                )
            )
        }
        return Result(out, skipped)
    }

    /** [lat, lon] of a Point, or the centre of a Polygon's outer ring. */
    private fun position(g: Map<*, *>): DoubleArray? {
        val c = g["coordinates"] as? List<*> ?: return null
        return when (g["type"]) {
            "Point" -> {
                val lon = c.getOrNull(0) as? Double ?: return null
                val lat = c.getOrNull(1) as? Double ?: return null
                doubleArrayOf(lat, lon)
            }
            "Polygon" -> {
                val ring = c.firstOrNull() as? List<*> ?: return null
                var la = 0.0; var lo = 0.0; var n = 0
                for (p in ring) {
                    val pt = p as? List<*> ?: continue
                    lo += pt.getOrNull(0) as? Double ?: continue
                    la += pt.getOrNull(1) as? Double ?: continue
                    n++
                }
                if (n == 0) null else doubleArrayOf(la / n, lo / n)
            }
            else -> null
        }
    }

    /** "2026-09-27T14:30:00Z" → epoch ms; null if the shape is unexpected. */
    fun isoMs(s: String): Long? = runCatching {
        val y = s.substring(0, 4).toInt()
        val mo = s.substring(5, 7).toInt()
        val d = s.substring(8, 10).toInt()
        val h = if (s.length >= 13) s.substring(11, 13).toInt() else 0
        val mi = if (s.length >= 16) s.substring(14, 16).toInt() else 0
        val sec = if (s.length >= 19) s.substring(17, 19).toInt() else 0
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val doy = cum[mo - 1] + d - 1 + if (leap && mo > 2) 1 else 0
        Tle.yearStartMs(y) + ((doy * 24L + h) * 60 + mi) * 60_000L + sec * 1000L
    }.getOrNull()
}
