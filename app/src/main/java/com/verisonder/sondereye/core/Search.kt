package com.verisonder.sondereye.core

import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Places by name from OpenStreetMap's Nominatim (no key; at most one request a second). */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

object Nominatim {
    fun url(q: String) = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" + URLEncoder.encode(q.trim(), "UTF-8")

    fun parse(text: String): List<Place> {
        val list = Json.parse(text) as? List<*> ?: throw Json.ParseError("Not a list")
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val lat = (m["lat"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = (m["lon"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
            val full = m["display_name"] as? String ?: return@mapNotNull null
            val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: full.substringBefore(',')
            Place(name, full, lat, lon)
        }
    }
}

/** Surveillance cameras mapped in OpenStreetMap, from the Overpass API (no key). */
data class Camera(val id: Long, val lat: Double, val lon: Double, val alpr: Boolean, val operator: String?, val direction: Double?)

object Overpass {
    const val URL = "https://overpass-api.de/api/interpreter"

    /** Query body (POST data=…) for cameras in a south/west/north/east box. */
    fun cameraQuery(s: Double, w: Double, n: Double, e: Double): String {
        val box = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, s, w, n, e)
        return "data=" + URLEncoder.encode(
            "[out:json][timeout:25];node[\"man_made\"=\"surveillance\"]($box);out 2000;", "UTF-8",
        )
    }

    fun parseCameras(text: String): List<Camera> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["remark"] as? String)?.let { if ("runtime error" in it) throw Json.ParseError(it.take(120)) }
        val els = root["elements"] as? List<*> ?: throw Json.ParseError("No elements")
        return els.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val tags = m["tags"] as? Map<*, *> ?: emptyMap<String, Any>()
            Camera(
                id = (m["id"] as? Double)?.toLong() ?: return@mapNotNull null,
                lat = m["lat"] as? Double ?: return@mapNotNull null,
                lon = m["lon"] as? Double ?: return@mapNotNull null,
                alpr = (tags["surveillance:type"] as? String)?.equals("ALPR", ignoreCase = true) == true,
                operator = tags["operator"] as? String,
                direction = (tags["camera:direction"] as? String ?: tags["direction"] as? String)?.toDoubleOrNull(),
            )
        }
    }
}

/** Hourly and daily forecast from Open-Meteo. */
class Forecast(val hours: List<Hour>, val days: List<Day>) {
    /** [timeLocal] is "2026-09-29T14:00" in the place's own time zone. */
    class Hour(val timeLocal: String, val tempC: Double, val rainChance: Int?, val code: Int)
    class Day(val dateLocal: String, val maxC: Double, val minC: Double, val rainChance: Int?, val code: Int)
}

object ForecastApi {
    fun url(lat: Double, lon: Double) = OpenMeteo.url(lat, lon) +
        "&hourly=temperature_2m,precipitation_probability,weather_code" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
        "&forecast_days=4&timezone=auto"

    fun parse(text: String): Pair<Weather, Forecast> {
        val now = OpenMeteo.parse(text)
        val root = Json.parse(text) as Map<*, *>
        val cur = (root["current"] as? Map<*, *>)?.get("time") as? String
        val h = root["hourly"] as? Map<*, *> ?: throw Json.ParseError("No hourly forecast")
        val ht = h["time"] as? List<*> ?: throw Json.ParseError("No hourly times")
        val start = ht.indexOfFirst { (it as? String ?: "") >= (cur?.take(13) ?: "") }.coerceAtLeast(0)
        val hours = (start until minOf(ht.size, start + 24)).map { i ->
            Forecast.Hour(
                ht[i] as String,
                (h["temperature_2m"] as List<*>)[i] as? Double ?: Double.NaN,
                ((h["precipitation_probability"] as? List<*>)?.getOrNull(i) as? Double)?.toInt(),
                ((h["weather_code"] as? List<*>)?.getOrNull(i) as? Double)?.toInt() ?: -1,
            )
        }
        val d = root["daily"] as? Map<*, *> ?: throw Json.ParseError("No daily forecast")
        val dt = d["time"] as? List<*> ?: emptyList<Any>()
        val days = dt.indices.map { i ->
            Forecast.Day(
                dt[i] as String,
                (d["temperature_2m_max"] as List<*>)[i] as? Double ?: Double.NaN,
                (d["temperature_2m_min"] as List<*>)[i] as? Double ?: Double.NaN,
                ((d["precipitation_probability_max"] as? List<*>)?.getOrNull(i) as? Double)?.toInt(),
                ((d["weather_code"] as? List<*>)?.getOrNull(i) as? Double)?.toInt() ?: -1,
            )
        }
        return now to Forecast(hours, days)
    }
}

object Motion {
    /**
     * Where something moving over the surface is after [seconds] at [speedKt] knots on
     * [trackDeg] (great circle). Used to glide aircraft between 10 s updates.
     */
    fun ahead(lat: Double, lon: Double, trackDeg: Double, speedKt: Double, seconds: Double): DoubleArray {
        val d = speedKt * 0.514444 * seconds / EARTH_R
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val tr = Math.toRadians(trackDeg)
        val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(tr))
        val lo2 = lo + atan2(sin(tr) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
        return doubleArrayOf(Math.toDegrees(la2), Geo.wrapLon(Math.toDegrees(lo2)))
    }
}

object Lookup {
    /** One aircraft by callsign, anywhere in the world (adsb.lol). */
    fun callsignUrl(cs: String) = "https://api.adsb.lol/v2/callsign/" + URLEncoder.encode(cs.trim().uppercase(), "UTF-8")

    /** Satellites whose name contains [q] (CelesTrak). */
    fun satelliteUrl(q: String) = "https://celestrak.org/NORAD/elements/gp.php?NAME=" + URLEncoder.encode(q.trim(), "UTF-8") + "&FORMAT=tle"
}
