package com.verisonder.sondereye.core

import java.util.Locale

/*
 * Layers whose sources need a free personal key. The user pastes the key in the app;
 * it never ships in the code.
 */

/** A vessel from AIS. [heading] is where the bow points, [cog] where it is moving. */
data class Ship(
    val mmsi: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val sogKt: Double?,
    val cog: Double?,
    val heading: Double?,
    val atMs: Long,
) {
    /** Which way to draw it: the bow when known, else the course. */
    val bearing: Double? get() = heading ?: cog
}

/** AISStream WebSocket messages. */
object Ais {
    const val URL = "wss://stream.aisstream.io/v0/stream"

    /** Sent within 3 s of connecting, or the server closes the socket. */
    fun subscription(key: String, s: Double, w: Double, n: Double, e: Double): String =
        "{\"APIKey\":" + Json.str(key) +
            ",\"BoundingBoxes\":[[[${f(s)},${f(w)}],[${f(n)},${f(e)}]]]" +
            ",\"FilterMessageTypes\":[\"PositionReport\",\"StandardClassBPositionReport\",\"ShipStaticData\"]}"

    private fun f(d: Double) = "%.4f".format(Locale.ROOT, d)

    sealed class Msg {
        class Position(val ship: Ship) : Msg()
        class Name(val mmsi: Long, val name: String) : Msg()
        class Error(val text: String) : Msg()
        object Other : Msg()
    }

    fun parse(text: String, nowMs: Long): Msg {
        val root = Json.parse(text) as? Map<*, *> ?: return Msg.Other
        (root["error"] as? String)?.let { return Msg.Error(it) }
        val type = root["MessageType"] as? String ?: return Msg.Other
        val meta = root["MetaData"] as? Map<*, *> ?: root["Metadata"] as? Map<*, *> ?: emptyMap<String, Any>()
        val body = (root["Message"] as? Map<*, *>)?.get(type) as? Map<*, *> ?: return Msg.Other
        val mmsi = ((body["UserID"] ?: meta["MMSI"]) as? Double)?.toLong() ?: return Msg.Other
        val name = (meta["ShipName"] as? String)?.trim()?.ifEmpty { null }
        return when (type) {
            "PositionReport", "StandardClassBPositionReport" -> {
                val lat = (body["Latitude"] ?: meta["latitude"] ?: meta["Latitude"]) as? Double ?: return Msg.Other
                val lon = (body["Longitude"] ?: meta["longitude"] ?: meta["Longitude"]) as? Double ?: return Msg.Other
                // AIS uses 91 / 181 for "no position".
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Msg.Other
                val heading = (body["TrueHeading"] as? Double)?.takeIf { it in 0.0..359.9 } // 511 = unknown
                val cog = (body["Cog"] as? Double)?.takeIf { it in 0.0..359.9 } // 360 = unknown
                val sog = (body["Sog"] as? Double)?.takeIf { it < 102.3 } // 102.3 = unknown
                Msg.Position(Ship(mmsi, name, lat, lon, sog, cog, heading, nowMs))
            }
            "ShipStaticData" -> ((body["Name"] as? String)?.trim()?.ifEmpty { null } ?: name)?.let { Msg.Name(mmsi, it) } ?: Msg.Other
            else -> Msg.Other
        }
    }
}

data class Webcam(
    val id: Long,
    val title: String,
    val lat: Double,
    val lon: Double,
    val place: String?,
    /** Current preview picture; Windy's links expire after a few minutes. */
    val preview: String?,
    val page: String?,
)

/** Windy Webcams API v3. Key in the x-windy-api-key header. */
object Windy {
    fun url(lat: Double, lon: Double, radiusKm: Int) =
        "https://api.windy.com/webcams/api/v3/webcams?nearby=" + "%.4f,%.4f,%d".format(Locale.ROOT, lat, lon, radiusKm.coerceIn(1, 250)) +
            "&limit=50&include=images,location,urls"

    /** The most popular webcams on Earth, 50 a page from [offset]: the view from space. */
    fun topUrl(offset: Int) =
        "https://api.windy.com/webcams/api/v3/webcams?sortKey=popularity&sortDirection=desc&limit=50&offset=$offset" +
            "&include=images,location,urls"

    fun parse(text: String): List<Webcam> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["message"] as? String)?.let { if (root["webcams"] == null) throw Json.ParseError(it) }
        val list = root["webcams"] as? List<*> ?: throw Json.ParseError("No webcams list")
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            if ((m["status"] as? String)?.let { it != "active" } == true) return@mapNotNull null
            val loc = m["location"] as? Map<*, *> ?: return@mapNotNull null
            val img = (m["images"] as? Map<*, *>)?.get("current") as? Map<*, *>
            Webcam(
                id = (m["webcamId"] as? Double)?.toLong() ?: return@mapNotNull null,
                title = (m["title"] as? String)?.trim() ?: "Webcam",
                lat = loc["latitude"] as? Double ?: return@mapNotNull null,
                lon = loc["longitude"] as? Double ?: return@mapNotNull null,
                place = listOfNotNull(loc["city"] as? String, loc["country"] as? String).joinToString(", ").ifEmpty { null },
                preview = (img?.get("preview") ?: img?.get("thumbnail")) as? String,
                page = ((m["urls"] as? Map<*, *>)?.get("detail") as? String),
            )
        }
    }
}

/** A fire detected by satellite (NASA FIRMS, VIIRS NOAA-20). */
data class Hotspot(
    val lat: Double,
    val lon: Double,
    /** Fire radiative power, megawatts: how intense. */
    val frpMw: Double?,
    /** "l", "n" or "h" (low, nominal, high). */
    val confidence: String?,
    /** "2026-09-29 1342" UTC. */
    val acquired: String,
    val day: Boolean,
)

object Firms {
    fun url(key: String, w: Double, s: Double, e: Double, n: Double, days: Int = 1) =
        "https://firms.modaps.eosdis.nasa.gov/api/area/csv/" + key.trim() + "/VIIRS_NOAA20_NRT/" +
            "%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, w, s, e, n) + "/" + days.coerceIn(1, 10)

    /** Every hotspot on Earth: FIRMS takes "world" as the area. */
    fun worldUrl(key: String, days: Int = 1) =
        "https://firms.modaps.eosdis.nasa.gov/api/area/csv/" + key.trim() + "/VIIRS_NOAA20_NRT/world/" + days.coerceIn(1, 10)

    /** The [n] most intense: a day of the whole Earth is tens of thousands, mostly faint. */
    fun strongest(list: List<Hotspot>, n: Int) = list.sortedByDescending { it.frpMw ?: 0.0 }.take(n)

    fun parse(text: String): List<Hotspot> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(',').map { it.trim() }
        if ("latitude" !in head) throw Json.ParseError(lines[0].take(120)) // FIRMS answers errors as plain text
        fun col(name: String) = head.indexOf(name)
        val la = col("latitude"); val lo = col("longitude"); val frp = col("frp"); val conf = col("confidence")
        val date = col("acq_date"); val time = col("acq_time"); val dn = col("daynight")
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            val lat = c.getOrNull(la)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = c.getOrNull(lo)?.toDoubleOrNull() ?: return@mapNotNull null
            Hotspot(
                lat, lon,
                c.getOrNull(frp)?.toDoubleOrNull(),
                c.getOrNull(conf)?.trim(),
                "${c.getOrNull(date) ?: ""} ${c.getOrNull(time)?.padStart(4, '0') ?: ""}".trim(),
                c.getOrNull(dn)?.trim() != "N",
            )
        }
    }
}
