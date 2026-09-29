package com.verisonder.sondereye.data

import com.verisonder.sondereye.core.Adsb
import com.verisonder.sondereye.core.Eonet
import com.verisonder.sondereye.core.Firms
import com.verisonder.sondereye.core.Gemini
import com.verisonder.sondereye.core.News
import com.verisonder.sondereye.core.NewsSource
import com.verisonder.sondereye.core.Story
import com.verisonder.sondereye.core.ForecastApi
import com.verisonder.sondereye.core.Windy
import com.verisonder.sondereye.core.Lookup
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Nominatim
import com.verisonder.sondereye.core.Overpass
import com.verisonder.sondereye.core.OpenMeteo
import com.verisonder.sondereye.core.RainViewer
import com.verisonder.sondereye.core.Period
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.core.Tle
import com.verisonder.sondereye.core.Usgs
import java.io.File

/** Every public feed the app reads. Blocking: call from Dispatchers.IO. */
object Feeds {
    fun quakes(min: MinMag, period: Period) =
        Net.get(Usgs.feedUrl(min, period), "Earthquakes", "USGS", Usgs::parse)

    fun flights(lat: Double, lon: Double) =
        Net.get(Adsb.url(lat, lon, Adsb.MAX_NM), "Flights", "adsb.lol", Adsb::parse)

    fun events() = Net.get(Eonet.URL, "Natural events", "NASA EONET", Eonet::parse)

    fun weather(lat: Double, lon: Double) = Net.get(OpenMeteo.url(lat, lon), "Weather", "Open-Meteo", OpenMeteo::parse)

    /** Radar frames: host and the past frame paths, oldest first. */
    fun radar() = Net.get(RainViewer.URL, "Rain radar", "RainViewer", RainViewer::frames)

    fun forecast(lat: Double, lon: Double) = Net.get(ForecastApi.url(lat, lon), "Weather", "Open-Meteo", ForecastApi::parse)

    fun places(q: String) = Net.get(Nominatim.url(q), "Search", "OpenStreetMap", Nominatim::parse)

    fun callsign(cs: String) = Net.get(Lookup.callsignUrl(cs), "Search", "adsb.lol", Adsb::parse)

    fun satellitesNamed(q: String) = Net.get(Lookup.satelliteUrl(q), "Search", "CelesTrak") { Tle.parseAll(it) }

    fun news(src: NewsSource) = Net.get(src.url, "News", src.name) { News.parseRss(src.name, it) }

    /** Tries each Gemini model name in turn: names change, the first that exists wins. */
    fun brief(key: String, stories: List<Story>, weather: String?, place: String?): Net.Outcome<String> {
        var last: Net.Outcome<String> = Net.Outcome.Failed("Brief: no Gemini model answered")
        for (m in Gemini.MODELS) {
            last = Net.postJson(Gemini.url(m, key), Gemini.request(stories, weather, place), "Brief", "Gemini", Gemini::parse)
            if (last is Net.Outcome.Ok) return last
            if (last is Net.Outcome.Failed && "HTTP 404" !in last.message) return last
        }
        return last
    }

    fun webcams(key: String, lat: Double, lon: Double, radiusKm: Int) =
        Net.getWith(Windy.url(lat, lon, radiusKm), mapOf("x-windy-api-key" to key.trim()), "Webcams", "Windy", Windy::parse)

    fun fires(key: String, w: Double, s: Double, e: Double, n: Double) =
        Net.get(Firms.url(key, w, s, e, n), "Fires", "NASA FIRMS", Firms::parse)

    fun cameras(s: Double, w: Double, n: Double, e: Double) =
        Net.post(Overpass.URL, Overpass.cameraQuery(s, w, n, e), "Cameras", "OpenStreetMap Overpass", Overpass::parseCameras)

    /**
     * Orbital elements, cached on disk for 2 hours as CelesTrak asks. When a download
     * fails, a stale copy is used and the failure is still reported.
     */
    fun satellites(cacheDir: File, group: String): Pair<Tle.Companion.Result?, String?> {
        val f = File(cacheDir, "tle-$group.txt")
        val age = System.currentTimeMillis() - f.lastModified()
        if (f.exists() && age < 2 * 3_600_000L) {
            runCatching { return Tle.parseAll(f.readText()) to null }
        }
        return when (val out = Net.get(Sky.celestrakUrl(group), "Satellites", "CelesTrak") { it }) {
            is Net.Outcome.Ok -> {
                val parsed = Tle.parseAll(out.value)
                if (parsed.tles.isEmpty()) {
                    // CelesTrak answers plain text errors with HTTP 200.
                    val stale = if (f.exists()) runCatching { Tle.parseAll(f.readText()) }.getOrNull() else null
                    stale to "Satellites: CelesTrak sent no orbits (${out.value.take(80).trim()})"
                } else {
                    runCatching { f.writeText(out.value) }
                    parsed to null
                }
            }
            is Net.Outcome.Failed -> {
                val stale = if (f.exists()) runCatching { Tle.parseAll(f.readText()) }.getOrNull() else null
                stale to (out.message + if (stale != null) ", showing the last copy" else "")
            }
        }
    }
}
