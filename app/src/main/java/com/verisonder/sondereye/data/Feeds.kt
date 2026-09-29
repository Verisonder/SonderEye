package com.verisonder.sondereye.data

import com.verisonder.sondereye.core.Adsb
import com.verisonder.sondereye.core.Eonet
import com.verisonder.sondereye.core.Firms
import com.verisonder.sondereye.core.Gemini
import com.verisonder.sondereye.core.News
import com.verisonder.sondereye.core.NewsSource
import com.verisonder.sondereye.core.Story
import com.verisonder.sondereye.core.ForecastApi
import com.verisonder.sondereye.core.Webcam
import com.verisonder.sondereye.core.Windy
import com.verisonder.sondereye.core.Bus
import com.verisonder.sondereye.core.BusLines
import com.verisonder.sondereye.core.GtfsRt
import com.verisonder.sondereye.core.RtFeed
import com.verisonder.sondereye.core.Transitland
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

    /**
     * Some news sites turn away requests that do not look like a browser; a browser-style
     * agent (still naming the app) gets the same public feed a feed reader would.
     */
    fun news(src: NewsSource) = Net.getWith(
        src.url,
        mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36 SonderEye",
            "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
        ),
        "News", src.name,
    ) { News.parseRss(src.name, it) }

    /** Tries each Gemini model name in turn: names change, the first that exists wins. */
    fun brief(key: String, stories: List<Story>, weather: String?, place: String?, prefs: com.verisonder.sondereye.core.BriefPrefs): Net.Outcome<String> {
        var last: Net.Outcome<String> = Net.Outcome.Failed("Brief: no Gemini model answered")
        for (m in Gemini.MODELS) {
            last = Net.postJson(Gemini.url(m, key), Gemini.request(stories, weather, place, prefs), "Brief", "Gemini", Gemini::parse)
            if (last is Net.Outcome.Ok) return last
            if (last is Net.Outcome.Failed && "HTTP 404" !in last.message) return last
        }
        return last
    }

    fun webcams(key: String, lat: Double, lon: Double, radiusKm: Int) =
        Net.getWith(Windy.url(lat, lon, radiusKm), mapOf("x-windy-api-key" to key.trim()), "Webcams", "Windy", Windy::parse)

    /** The [pages] × 50 most popular webcams worldwide. A page that fails after the first ends the list there. */
    fun topWebcams(key: String, pages: Int): Net.Outcome<List<Webcam>> {
        val all = ArrayList<Webcam>()
        for (p in 0 until pages) {
            when (val o = Net.getWith(Windy.topUrl(p * 50), mapOf("x-windy-api-key" to key.trim()), "Webcams", "Windy", Windy::parse)) {
                is Net.Outcome.Ok -> all += o.value
                is Net.Outcome.Failed -> if (all.isEmpty()) return o else break
            }
        }
        return Net.Outcome.Ok(all.distinctBy { it.id })
    }

    fun conflicts() = Net.get(com.verisonder.sondereye.core.Gdelt.URL, "Conflicts", "GDELT", com.verisonder.sondereye.core.Gdelt::parse)

    fun busLines(s: Double, w: Double, n: Double, e: Double) =
        Net.post(Overpass.URL, BusLines.query(s, w, n, e), "Bus lines", "OpenStreetMap Overpass", BusLines::parse)

    /** Realtime feeds of the operators serving the point. */
    fun busFeeds(key: String, lat: Double, lon: Double, radiusM: Int) =
        Net.get(Transitland.operatorsUrl(lat, lon, radiusM, key), "Live buses", "Transitland", Transitland::parseRtFeeds)

    /** How a feed's vehicles are read: Transitland's copy, the source itself, or not at all (the source wants its own key). */
    sealed class RtWay {
        object Cached : RtWay()
        class Direct(val url: String) : RtWay()
        object NeedsOwnKey : RtWay()
    }

    /**
     * Vehicles from every feed, and the names of feeds that cannot be read. Transitland shares
     * its copy of a feed only where the licence allows; otherwise the feed is read at its
     * source, when that needs no key of its own. [ways] remembers what worked, per feed.
     */
    fun buses(key: String, feeds: List<RtFeed>, ways: MutableMap<String, RtWay>): Net.Outcome<Pair<List<Bus>, List<String>>> {
        val buses = ArrayList<Bus>()
        val unreadable = ArrayList<String>()
        var lastFail: Net.Outcome.Failed? = null
        for (f in feeds) {
            var way = ways[f.onestopId]
            if (way == null || way is RtWay.Cached) {
                when (val o = Net.get(Transitland.vehiclesUrl(f.onestopId, key), "Live buses", "Transitland") { Transitland.parseVehicles(it, f) }) {
                    is Net.Outcome.Ok -> {
                        ways[f.onestopId] = RtWay.Cached
                        buses += o.value
                        continue
                    }
                    is Net.Outcome.Failed -> {
                        if (o.code == 429 || way != null) { // slow down, or it worked before: say so
                            lastFail = o
                            if (o.code == 429) break else continue
                        }
                    }
                }
                val src = Net.get(Transitland.feedUrl(f.onestopId, key), "Live buses", "Transitland", Transitland::parseSource)
                if (src !is Net.Outcome.Ok) {
                    lastFail = src as Net.Outcome.Failed
                    continue // try again next time
                }
                val url = src.value.vehiclesUrl
                way = if (url != null && !src.value.needsKey) RtWay.Direct(url) else RtWay.NeedsOwnKey
                ways[f.onestopId] = way
            }
            when (way) {
                is RtWay.Direct -> when (val o = Net.getBytes(way.url, "Live buses", f.name) { GtfsRt.parseVehicles(it, f) }) {
                    is Net.Outcome.Ok -> buses += o.value
                    is Net.Outcome.Failed -> lastFail = o
                }
                RtWay.NeedsOwnKey -> unreadable += f.name
                else -> {}
            }
        }
        val fail = lastFail
        if (buses.isEmpty() && fail != null) return fail
        return Net.Outcome.Ok(buses to unreadable)
    }

    fun fires(key: String, w: Double, s: Double, e: Double, n: Double) =
        Net.get(Firms.url(key, w, s, e, n), "Fires", "NASA FIRMS", Firms::parse)

    fun firesWorld(key: String) = Net.get(Firms.worldUrl(key), "Fires", "NASA FIRMS", Firms::parse)

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
