package com.verisonder.sondereye.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.verisonder.sondereye.core.Ais
import com.verisonder.sondereye.core.Bus
import com.verisonder.sondereye.core.BusLine
import com.verisonder.sondereye.core.BusStop
import com.verisonder.sondereye.core.Camera
import com.verisonder.sondereye.core.Conflict
import com.verisonder.sondereye.core.RtFeed
import com.verisonder.sondereye.core.Firms
import com.verisonder.sondereye.core.Hotspot
import com.verisonder.sondereye.core.BriefCache
import com.verisonder.sondereye.core.BriefPrefs
import com.verisonder.sondereye.core.News
import com.verisonder.sondereye.core.Ship
import com.verisonder.sondereye.core.Story
import com.verisonder.sondereye.core.Webcam
import com.verisonder.sondereye.core.EARTH_R
import com.verisonder.sondereye.core.Forecast
import com.verisonder.sondereye.core.Motion
import com.verisonder.sondereye.core.Place
import com.verisonder.sondereye.core.Flight
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.NatEvent
import com.verisonder.sondereye.core.Pass
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.core.TileSource
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.core.Weather
import com.verisonder.sondereye.alerts.PassAlerts
import com.verisonder.sondereye.data.AisStream
import com.verisonder.sondereye.data.Feeds
import com.verisonder.sondereye.data.Keys
import com.verisonder.sondereye.data.Layers
import com.verisonder.sondereye.data.MapStyle
import com.verisonder.sondereye.data.Net
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.data.Where
import com.verisonder.sondereye.globe.GlobeLine
import com.verisonder.sondereye.globe.GlobeStatus
import com.verisonder.sondereye.globe.GlobeView
import com.verisonder.sondereye.globe.Marker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** One layer's download state. */
class Feed<T> {
    var items by mutableStateOf<List<T>>(emptyList())
    var loading by mutableStateOf(false)
    /** Last successful download (shown). */
    var updatedAt by mutableStateOf<Long?>(null)
    /** Last attempt, successful or not (drives the schedule). */
    var attemptAt = 0L
    var error by mutableStateOf<String?>(null)
    var skipped by mutableIntStateOf(0)
}

/** What a tap selected. The key matches the marker's. */
sealed class Sel(val key: String) {
    class OfQuake(val q: Quake) : Sel("q:" + q.id)
    class OfFlight(val f: Flight) : Sel("f:" + f.hex)
    class OfSat(val s: Sgp4) : Sel("s:" + s.tle.norad)
    class OfEvent(val e: NatEvent) : Sel("e:" + e.id)
    object Me : Sel("me")
    class OfCamera(val c: Camera) : Sel("c:" + c.id)
    class OfShip(val s: Ship) : Sel("v:" + s.mmsi)
    class OfWebcam(val w: Webcam) : Sel("w:" + w.id)
    class OfFire(val h: Hotspot) : Sel("h:%.4f,%.4f".format(java.util.Locale.ROOT, h.lat, h.lon))
    class OfConflict(val c: Conflict) : Sel(c.key)
    class OfBusStop(val s: BusStop) : Sel("bs:" + s.id)
    class OfBusLine(val l: BusLine) : Sel("bl:" + l.id)
    class OfBus(val b: Bus) : Sel("bv:" + b.key)
    /** A spot picked by a long press, for its weather. */
    class OfPlace(val lat: Double, val lon: Double) : Sel("p:%.4f,%.4f".format(java.util.Locale.ROOT, lat, lon))
}

/** Weather for the selected spot (or for you). */
class WeatherState(val key: String) {
    var weather by mutableStateOf<Weather?>(null)
    var forecast by mutableStateOf<Forecast?>(null)
    var error by mutableStateOf<String?>(null)
}

/** One search hit. */
sealed class Hit(val title: String, val detail: String) {
    class OfPlace(val p: Place) : Hit(p.name, p.detail)
    class OfFlight(val f: Flight) : Hit(f.callsign ?: f.hex.uppercase(), listOfNotNull("Flight", f.type, f.registration).joinToString(", "))
    class OfSat(val s: Sgp4) : Hit(s.tle.name, "Satellite, NORAD ${s.tle.norad}")
}

/** The day's brief: weather where you are, the top stories, and an optional written summary. */
class BriefState {
    var open by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var weather by mutableStateOf<Weather?>(null)
    var forecast by mutableStateOf<Forecast?>(null)
    var weatherProblem by mutableStateOf<String?>(null)
    var stories by mutableStateOf<List<Story>>(emptyList())
    var newsProblems by mutableStateOf<List<String>>(emptyList())
    var summary by mutableStateOf<String?>(null)
    var summaryProblem by mutableStateOf<String?>(null)
    var loadedAt = 0L
    /** When the weather part was last fetched. */
    var weatherAt = 0L
    /** Local date ("2026-09-29") the news and summary were made for. */
    var day by mutableStateOf<String?>(null)
    /** The written summary is being made (first time today, or regenerating). */
    var writing by mutableStateOf(false)
    var prefs by mutableStateOf(BriefPrefs())
    /** The customise section is open. */
    var editing by mutableStateOf(false)
}

class SearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Hit>>(emptyList())
    var busy by mutableStateOf(false)
    var errors by mutableStateOf<List<String>>(emptyList())
    var searched by mutableStateOf(false)
}

/** Everything the screen shows. Plain Compose state; the activity survives rotation (manifest). */
class EyeState {
    var layers by mutableStateOf(Layers())
    val quakes = Feed<Quake>()
    val flights = Feed<Flight>()
    val sats = Feed<Sgp4>()
    val events = Feed<NatEvent>()
    /** Satellites whose orbits need SDP4 (deep space); not drawn, but counted. */
    var satsDeep by mutableIntStateOf(0)

    var me by mutableStateOf<Location?>(null)
    var meProblem by mutableStateOf<String?>(null)

    var globeStatus by mutableStateOf<GlobeStatus?>(null)
    var globeError by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Sel?>(null)
    var passes by mutableStateOf<List<Pass>?>(null)
    var weather by mutableStateOf<WeatherState?>(null)
    /** [host, path] of the latest radar frame. */
    val radar = Feed<String>()
    var alertProblem by mutableStateOf<String?>(null)
    /** Credits for the map layers on screen, as the providers ask. */
    var credits by mutableStateOf(listOf(TileSource.SATELLITE.credit))
    val cameras = Feed<Camera>()
    /** Why cameras are not loading right now (too far out), shown dimly. */
    var camerasNote by mutableStateOf<String?>(null)
    /** Hex of the aircraft the camera follows. */
    var following by mutableStateOf<String?>(null)
    val search = SearchState()
    val brief = BriefState()
    /** The layer whose full list is open ("quakes", "flights"…), or null. */
    var listLayer by mutableStateOf<String?>(null)
    /** Clean view: only the globe (and cards you open by tapping). */
    var chromeHidden by mutableStateOf(false)
    /** The legend alone tucked away; a tab at the left edge brings it back. */
    var legendHidden by mutableStateOf(false)
    /** Every source in use, for the menu's list (the screen shows only the required ones). */
    var allCredits by mutableStateOf<List<String>>(emptyList())
    /** Time of the radar frame on screen. */
    var radarFrameAt by mutableStateOf<Long?>(null)
    /** Bytes of map tiles on the phone (shown in the menu). */
    var cacheBytes by mutableStateOf<Long?>(null)
    var keys by mutableStateOf(Keys())
    /** Live ships by MMSI; replaced (not mutated) so Compose sees changes. */
    var ships by mutableStateOf<Map<Long, Ship>>(emptyMap())
    var shipsNote by mutableStateOf<String?>(null)
    /** Flights are rate-limited: shown quietly in the legend. */
    var flightsNote by mutableStateOf<String?>(null)
    var shipsProblem by mutableStateOf<String?>(null)
    val webcams = Feed<Webcam>()
    var webcamsNote by mutableStateOf<String?>(null)
    /** Showing the most popular webcams worldwide (from high up), not those near the centre. */
    var webcamsWorld by mutableStateOf(false)
    val fires = Feed<Hotspot>()
    var firesNote by mutableStateOf<String?>(null)
    /** Showing the strongest fires worldwide (from high up), not all of them around the view. */
    var firesWorld by mutableStateOf(false)
    /** Places in the news for fighting, last 24 h (GDELT). */
    val conflicts = Feed<Conflict>()
    /** Bus lines (one per direction) and their stops, OpenStreetMap. */
    val busLines = Feed<BusLine>()
    var busStops by mutableStateOf<List<BusStop>>(emptyList())
    var busLinesNote by mutableStateOf<String?>(null)
    /** Live buses, and what the legend says about where they come from. */
    val buses = Feed<Bus>()
    var busesNote by mutableStateOf<String?>(null)
    var busFeeds by mutableStateOf<List<RtFeed>>(emptyList())
    /** Feeds found here that want their own key (their operator's), so show nothing. */
    var busFeedsUnreadable by mutableStateOf<List<String>>(emptyList())
    /** Satellites added by a search, drawn even when their group is not shown. */
    var extraSats by mutableStateOf<List<Sgp4>>(emptyList())
    /** Screen centre and camera, for the position readout: lat, lon, alt, heading. */
    var view by mutableStateOf<DoubleArray?>(null)
    var layersOpen by mutableStateOf(false)
    /** Wall clock for moving things, ticking once a second while the app is on screen. */
    var clock by mutableLongStateOf(System.currentTimeMillis())
}

class MainActivity : ComponentActivity() {

    private val state = EyeState()
    private lateinit var store: Settings
    private lateinit var where: Where
    private var globe: GlobeView? = null
    private val jobs = HashMap<String, Job>()
    private var flyToMeOnFix = false
    /** Height to fly to once the position arrives: city view, or street view (long-press). */
    private var flyToMeAlt = ME_ALT
    /** Radar frame path on screen (null: the latest). */
    private var radarFrame: String? = null
    /** Android stops showing the permission dialog after repeated denials. */
    private var locationBlocked = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) schedulePassAlerts()
        else state.alertProblem = "Pass alerts: notifications are blocked for SonderEye. Allow them in the app's settings."
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) {
            locationBlocked = false
            state.meProblem = null
            where.start()
        } else {
            locationBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            state.meProblem = if (locationBlocked) "Location: permission denied. Tap to open the app's settings."
            else "Location: permission denied. Tap to ask again."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Settings(this)
        state.layers = store.load()
        state.keys = store.keys()
        state.brief.prefs = store.brief()
        Palette.dark = !state.layers.lightPanels
        installHttpCache()
        where = Where(
            this,
            onFix = { loc ->
                val first = state.me == null
                state.me = loc
                state.meProblem = null
                if (first) {
                    store.saveHome(loc.latitude, loc.longitude)
                    if (state.layers.passAlerts) schedulePassAlerts()
                }
                globe?.setLayer("me", listOf(meMarker(loc)))
                if (flyToMeOnFix) {
                    flyToMeOnFix = false
                    globe?.flyTo(loc.latitude, loc.longitude, flyToMeAlt)
                }
                if (state.passes == null) recomputePasses()
            },
            onProblem = { state.meProblem = it },
        )

        val gl = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
        if (gl >= 0x30000) {
            globe = GlobeView(this, object : GlobeView.Listener {
                override fun onTap(key: String?) = select(key?.let(::selectionFor))
                override fun onUserGesture() { state.following = null }
                override fun onLongPress(lat: Double, lon: Double) {
                    val sel = Sel.OfPlace(lat, lon)
                    select(sel)
                    globe?.setLayer("pin", listOf(Marker(sel.key, lat, lon, 16f * density, Palette.accent.toArgb())))
                    globe?.select(sel.key, fly = false)
                }
                override fun onStatus(status: GlobeStatus) { state.globeStatus = status }
                override fun onError(message: String) { state.globeError = message }
            })
            // Fixes the draw order: later layers on top.
            for (name in listOf("fires", "quakes", "conflicts", "events", "cameras", "busStops", "buses", "webcams", "ships", "flights", "sats", "pin", "me")) globe?.setLayer(name, emptyList())
            applyMap()
            globe?.northLocked = state.layers.northLock
        } else {
            state.globeError = "Globe: this phone reports OpenGL ES ${gl shr 16}.${gl and 0xFFFF}; 3.0 is required"
        }

        setContent {
            EyeTheme {
                EyeScreen(
                    state = state,
                    globeView = globe,
                    actions = Actions(
                        refresh = ::refreshAll,
                        change = ::change,
                        select = { sel ->
                            select(sel)
                            globe?.select(sel?.key, fly = false)
                        },
                        home = {
                            select(null)
                            globe?.select(null, fly = false)
                            globe?.home()
                        },
                        zoomHold = { rate -> globe?.zoomHold(rate) },
                        myLocation = { myLocation() },
                        myLocationClose = { myLocation(ME_CLOSE_ALT) },
                        fixLocation = ::fixLocation,
                        sky = { startActivity(Intent(this, SkyActivity::class.java)) },
                        search = ::search,
                        pick = ::pick,
                        follow = { hex ->
                            state.following = hex
                            if (hex != null) flightAt(hex, System.currentTimeMillis())?.let { globe?.flyTo(it[0], it[1], globe!!.center()[2].coerceAtMost(300_000.0)) }
                        },
                        clearCache = ::clearCache,
                        measureCache = ::measureCache,
                        northUp = { globe?.northUp() },
                        openList = { layer ->
                            state.listLayer = layer
                            if (layer != null) {
                                state.layersOpen = false; state.search.open = false; state.brief.open = false
                            }
                        },
                        goTo = { sel ->
                            state.listLayer = null
                            select(sel)
                            globe?.select(sel.key, fly = busPlace(sel) == null)
                            // Buses and their lines are street-scale: stay close instead of flying out.
                            busPlace(sel)?.let { p -> globe?.flyTo(p[0], p[1], globe!!.center()[2].coerceIn(1_500.0, BUS_FLY_ALT)) }
                        },
                        brief = { open ->
                            state.brief.open = open
                            if (open) {
                                state.layersOpen = false
                                state.search.open = false
                                // Weather goes stale in minutes; the news and summary are made once a day.
                                if (System.currentTimeMillis() - state.brief.weatherAt > 20 * 60_000L) loadBriefWeather()
                                if (state.brief.day != today() && !state.brief.loading) loadBriefNews()
                            }
                        },
                        reloadBrief = { loadBriefWeather(); loadBriefNews() },
                        regenerateSummary = { loadBriefNews(summaryOnly = true) },
                        briefPrefs = { p ->
                            state.brief.prefs = p
                            store.saveBrief(p)
                        },
                        saveKeys = { k ->
                            store.saveKeys(k)
                            state.keys = store.keys()
                            closeShips(); loadWebcams(force = true); loadFires(force = true)
                        },
                    ),
                )
            }
        }

        refreshAll()
        if (state.layers.passAlerts) schedulePassAlerts()
        restoreBrief()

        // Position readout: four times a second, and only when it changed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val c = globe?.center()
                    if (c != null && !c.contentEquals(state.view)) state.view = c
                    delay(250)
                }
            }
        }

        // Radar animation: the past hour's frames in a loop, holding on the latest.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var i = 0
                while (true) {
                    val frames = state.radar.items.drop(1).takeLast(RADAR_FRAMES)
                    if (state.layers.radar && state.layers.radarLoop && frames.isNotEmpty()) {
                        i = (i + 1) % frames.size
                        radarFrame = frames[i]
                        applyMap()
                        delay(if (i == frames.size - 1) 1800 else 600)
                    } else {
                        delay(1000)
                    }
                }
            }
        }

        // Everything below runs only while the app is on screen. Nothing in the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (state.layers.location && hasLocationPermission()) where.start()
                // First time the app is on screen today: make today's brief in the background.
                if (state.brief.day != today() && !state.brief.loading) {
                    loadBriefWeather()
                    loadBriefNews()
                }
                try {
                    var tick = 0L
                    while (true) {
                        val l = state.layers
                        val now = System.currentTimeMillis()
                        state.clock = now
                        if ((l.satellites && state.sats.items.isNotEmpty()) || state.extraSats.isNotEmpty()) updateSatellites(now, orbit = tick % 30 == 0L)
                        if (l.flights && !state.flights.loading &&
                            now - state.flights.attemptAt >= maxOf(FLIGHTS_MS, flightsBackoffS * 1000L)
                        ) loadFlights()
                        if (l.radar && !state.radar.loading && now - state.radar.attemptAt >= RADAR_MS) loadRadar()
                        if (l.flights && state.flights.items.isNotEmpty()) glideFlights(now)
                        if (l.cameras && !state.cameras.loading && now - state.cameras.attemptAt >= 15_000 && camerasStale()) loadCameras()
                        if (l.busLines) {
                            if (!state.busLines.loading && now - state.busLines.attemptAt >= 15_000 && busArea.stale(globe?.center())) loadBusLines()
                            showBusStops()
                        }
                        if (l.conflicts && !state.conflicts.loading && now - state.conflicts.attemptAt >= CONFLICTS_MS) loadConflicts()
                        if (l.buses && !state.buses.loading && now - state.buses.attemptAt >= maxOf(BUSES_MS, busesBackoffS * 1000L)) loadBuses()
                        if (l.ships) tickShips(now) else if (shipsOpen) closeShips()
                        if (l.webcams && !state.webcams.loading && now - state.webcams.attemptAt >= 15_000 && webcamArea.stale(globe?.center())) loadWebcams()
                        if (l.fires && !state.fires.loading &&
                            (now - state.fires.attemptAt >= 30 * 60_000L || (now - state.fires.attemptAt >= 15_000 && fireArea.stale(globe?.center())))
                        ) loadFires()
                        if (l.quakes && l.autoRefresh && !state.quakes.loading && now - state.quakes.attemptAt >= QUAKES_AUTO_MS) loadQuakes()
                        tick++
                        delay(1000)
                    }
                } finally {
                    where.stop()
                }
            }
        }
    }

    // ---- Settings ---------------------------------------------------------------------

    private fun change(new: Layers) {
        val old = state.layers
        state.layers = new
        store.save(new)
        if (new.quakes != old.quakes || new.minMag != old.minMag || new.period != old.period) loadQuakes()
        if (new.flights != old.flights) loadFlights()
        if (new.satellites != old.satellites || new.satGroup != old.satGroup) loadSatellites()
        if (new.events != old.events) loadEvents()
        if (new.map != old.map || new.roads != old.roads || new.labels != old.labels ||
            new.dayNight != old.dayNight || new.lights != old.lights
        ) applyMap()
        if (new.trails != old.trails) glideFlights(System.currentTimeMillis())
        if (new.cameras != old.cameras) loadCameras()
        if (new.busLines != old.busLines) loadBusLines(force = true)
        if (new.buses != old.buses) loadBuses(force = true)
        if (new.conflicts != old.conflicts) loadConflicts()
        Palette.dark = !new.lightPanels
        if (new.northLock != old.northLock) globe?.northLocked = new.northLock
        if (new.credits != old.credits || new.cameras != old.cameras || new.busLines != old.busLines) applyMap()
        if (new.ships != old.ships && !new.ships) closeShips()
        if (new.webcams != old.webcams) loadWebcams(force = true)
        if (new.fires != old.fires) loadFires(force = true)
        if (new.radar != old.radar) loadRadar()
        if (new.radarLoop != old.radarLoop && !new.radarLoop) {
            radarFrame = null // back to the latest frame
            applyMap()
        }
        if (new.passAlerts != old.passAlerts) {
            if (new.passAlerts) enablePassAlerts() else {
                PassAlerts.cancel(this)
                state.alertProblem = null
            }
        }
        if (new.location != old.location) {
            if (new.location) {
                myLocation()
            } else {
                where.stop()
                state.me = null
                state.meProblem = null
                globe?.setLayer("me", emptyList())
                if (state.selected is Sel.Me) select(null)
            }
        }
    }

    private fun refreshAll() {
        loadQuakes(); loadFlights(); loadSatellites(); loadEvents(); loadRadar()
        if (state.layers.conflicts) loadConflicts()
        val w = state.weather
        if (w != null) state.selected?.let { loadWeather(it) }
    }

    // ---- Map and weather ----------------------------------------------------------------

    /** Base map and overlays from the settings (and the radar frame, when on). */
    private fun applyMap() {
        val l = state.layers
        val base = when (l.map) {
            MapStyle.SATELLITE -> TileSource.SATELLITE
            MapStyle.STREETS -> TileSource.STREETS
            MapStyle.TODAY -> TileSource.TODAY
        }
        val overlays = ArrayList<TileSource>()
        // Streets already draws its own roads and names.
        if (l.map != MapStyle.STREETS && l.roads) overlays.add(TileSource.ROADS)
        if (l.map != MapStyle.STREETS && l.labels) overlays.add(TileSource.LABELS)
        if (l.dayNight && l.lights) overlays.add(0, TileSource.LIGHTS)
        val r = state.radar.items
        val frame = radarFrame ?: r.lastOrNull()
        if (l.radar && r.size >= 2 && frame != null) {
            overlays.add(TileSource.radar(r[0], com.verisonder.sondereye.core.RainViewer.framePath(frame)))
            state.radarFrameAt = com.verisonder.sondereye.core.RainViewer.frameTimeMs(frame) ?: state.radar.updatedAt
        }
        globe?.setMap(base, overlays)
        globe?.setDayNight(l.dayNight)
        applyCredits(base, overlays)
    }

    /**
     * Esri, RainViewer and OpenStreetMap require credit where their data is shown; NASA
     * only asks. The screen shows the required ones (when the setting is on); the menu
     * lists everything.
     */
    private fun applyCredits(base: TileSource, overlays: List<TileSource>) {
        val shown = listOf(base) + overlays
        val all = (shown + TileSource.BLUE_MARBLE).map { it.credit }.toMutableList()
        val required = shown.filter { it.id.startsWith("esri") || it.id.startsWith("rain") }.map { it.credit }.toMutableList()
        if (state.layers.cameras || state.layers.busLines || state.search.searched) {
            all.add("© OpenStreetMap contributors"); required.add("© OpenStreetMap contributors")
        }
        state.allCredits = all.distinct()
        state.credits = if (state.layers.credits) required.distinct() else emptyList()
    }

    // ---- Daily brief --------------------------------------------------------------------

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

    private val briefFile get() = File(filesDir, "brief.json")

    /** Today's brief from the phone, if it was already made today. */
    private fun restoreBrief() {
        val saved = runCatching { BriefCache.decode(briefFile.readText()) }.getOrNull() ?: return
        if (saved.day != today()) return
        val b = state.brief
        b.day = saved.day
        b.stories = saved.stories
        b.summary = saved.summary
        b.loadedAt = saved.atMs
    }

    private fun loadBriefWeather() {
        val b = state.brief
        val p = b.prefs
        run("brief-weather") {
            val here = state.me?.let { doubleArrayOf(it.latitude, it.longitude) } ?: store.home()
            if (!p.weather) {
                b.weather = null; b.forecast = null; b.weatherProblem = null
            } else if (here == null) {
                b.weatherProblem = "Weather: your location is not known yet. Tap the pin once, then open this again."
            } else {
                when (val w = withContext(Dispatchers.IO) { Feeds.forecast(here[0], here[1]) }) {
                    is Net.Outcome.Ok -> { b.weather = w.value.first; b.forecast = w.value.second; b.weatherProblem = null; b.weatherAt = System.currentTimeMillis() }
                    is Net.Outcome.Failed -> b.weatherProblem = w.message
                }
            }
        }
    }

    /**
     * Today's stories and, with a Gemini key, the written summary. Made on the first open
     * of each day and on "Regenerate"; saved on the phone for the rest of the day. A failed
     * regeneration keeps the summary that was there.
     */
    private fun loadBriefNews(summaryOnly: Boolean = false) {
        val b = state.brief
        val p = b.prefs
        b.loading = !summaryOnly
        b.newsProblems = if (summaryOnly) b.newsProblems else emptyList()
        b.summaryProblem = null
        run("brief") {
            if (!summaryOnly) {
                // Every feed at once; one failing does not stop the others.
                val results = withContext(Dispatchers.IO) {
                    News.sourcesFor(p).map { src -> async { Feeds.news(src) } }.map { it.await() }
                }
                val stories = ArrayList<Story>()
                val problems = ArrayList<String>()
                for (r in results) when (r) {
                    is Net.Outcome.Ok -> stories.addAll(r.value)
                    is Net.Outcome.Failed -> problems.add(r.message)
                }
                if (News.sourcesFor(p).isEmpty()) problems.add("News: no sources chosen. Tap Customise.")
                val fresh = News.today(News.filter(stories, p.include, p.exclude), System.currentTimeMillis(), p.stories)
                b.newsProblems = problems
                // Every feed failed (no connection): keep what we had rather than blank it.
                if (fresh.isNotEmpty() || b.stories.isEmpty()) b.stories = fresh
                b.loadedAt = System.currentTimeMillis()
                b.loading = false
            }
            // The written brief, only with the user's own Gemini key.
            val key = state.keys.gemini
            if (key.isNotEmpty() && b.stories.isNotEmpty()) {
                b.writing = true
                val weatherLine = b.weather?.let { w ->
                    val d = b.forecast?.days?.firstOrNull()
                    "${w.tempC.roundToInt()} °C now, ${w.description.lowercase()}" + (d?.let { ", high ${it.maxC.roundToInt()} °C, low ${it.minC.roundToInt()} °C" } ?: "")
                }
                when (val out = withContext(Dispatchers.IO) { Feeds.brief(key, b.stories, weatherLine, null, p) }) {
                    is Net.Outcome.Ok -> b.summary = out.value
                    is Net.Outcome.Failed -> b.summaryProblem = out.message // the previous summary stays
                }
                b.writing = false
            }
            if (b.stories.isNotEmpty()) {
                b.day = today()
                val text = BriefCache.encode(today(), b.loadedAt, b.summary, b.stories)
                withContext(Dispatchers.IO) { runCatching { briefFile.writeText(text) } }
            }
        }
    }

    private fun loadRadar() {
        if (!state.layers.radar) {
            state.radar.items = emptyList(); state.radar.error = null; state.radar.updatedAt = null
            applyMap()
            return
        }
        state.radar.loading = true
        state.radar.attemptAt = System.currentTimeMillis()
        run("radar") {
            val out = withContext(Dispatchers.IO) { Feeds.radar() }
            state.radar.loading = false
            when (out) {
                is Net.Outcome.Ok -> {
                    state.radar.items = listOf(out.value.first) + out.value.second
                    radarFrame = null
                    state.radar.updatedAt = System.currentTimeMillis()
                    state.radar.error = null
                    applyMap()
                }
                is Net.Outcome.Failed -> state.radar.error = out.message
            }
        }
    }

    /** Current weather for a long-pressed spot or for you. */
    private fun loadWeather(sel: Sel) {
        val ll = when (sel) {
            is Sel.OfPlace -> doubleArrayOf(sel.lat, sel.lon)
            Sel.Me -> state.me?.let { doubleArrayOf(it.latitude, it.longitude) }
            else -> null
        } ?: return
        val w = WeatherState(sel.key)
        state.weather = w
        run("weather") {
            when (val out = withContext(Dispatchers.IO) { Feeds.forecast(ll[0], ll[1]) }) {
                is Net.Outcome.Ok -> {
                    w.weather = out.value.first
                    w.forecast = out.value.second
                }
                is Net.Outcome.Failed -> w.error = out.message
            }
        }
    }

    // ---- Pass alerts --------------------------------------------------------------------

    private fun enablePassAlerts() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            schedulePassAlerts()
        }
        if (store.home() == null && state.me == null) myLocation()
    }

    private fun schedulePassAlerts() {
        PassAlerts.ensureChannel(this)
        PassAlerts.schedule(this) { problem -> runOnUiThread { state.alertProblem = problem } }
    }

    // ---- Layers -------------------------------------------------------------------------

    /** Runs [block] as the only job named [name]; a newer call replaces an older one. */
    private fun run(name: String, block: suspend () -> Unit) {
        jobs[name]?.cancel()
        jobs[name] = lifecycleScope.launch { block() }
    }

    private fun <T> clear(feed: Feed<T>, layer: String, prefix: String = "${layer.first()}:") {
        jobs[layer]?.cancel()
        feed.items = emptyList(); feed.loading = false; feed.error = null; feed.updatedAt = null
        globe?.setLayer(layer, emptyList())
        if (state.selected?.key?.startsWith(prefix) == true) select(null)
    }

    private fun <T> settle(feed: Feed<T>, out: Net.Outcome<Pair<List<T>, Int>>, layer: String, markers: (List<T>) -> List<Marker>) {
        feed.loading = false
        when (out) {
            is Net.Outcome.Ok -> {
                feed.items = out.value.first
                feed.skipped = out.value.second
                feed.updatedAt = System.currentTimeMillis()
                feed.error = null
                globe?.setLayer(layer, markers(out.value.first))
                refreshSelection()
            }
            // Old markers stay; the error says the data is not current.
            is Net.Outcome.Failed -> feed.error = out.message
        }
    }

    private fun loadQuakes() {
        val l = state.layers
        if (!l.quakes) return clear(state.quakes, "quakes")
        state.quakes.loading = true
        state.quakes.attemptAt = System.currentTimeMillis()
        run("quakes") {
            val out = withContext(Dispatchers.IO) { Feeds.quakes(l.minMag, l.period) }
            settle(state.quakes, map(out) { it.quakes to it.skipped }, "quakes") { list -> list.map(::quakeMarker) }
        }
    }

    /** Aircraft within 250 nm of wherever the screen centre is now. */
    private fun loadFlights() {
        if (!state.layers.flights) return clear(state.flights, "flights")
        // Around the followed aircraft (so it never drops out), otherwise the screen centre.
        val c = state.following?.let { flightAt(it, System.currentTimeMillis()) } ?: globe?.center() ?: return
        state.flights.loading = true
        state.flights.attemptAt = System.currentTimeMillis()
        run("flights") {
            val out = withContext(Dispatchers.IO) { Feeds.flights(c[0], c[1]) }
            val at = System.currentTimeMillis()
            if (out is Net.Outcome.Failed && out.code == 429) {
                // Rate-limited: wait longer each time (or as long as the server says), keep
                // the planes we have gliding, and say so quietly rather than as an error.
                flightsBackoffS = (out.retryAfterS ?: (flightsBackoffS * 2).coerceAtLeast(30)).coerceIn(15, 300)
                flightsLimitedSince = if (flightsLimitedSince == 0L) at else flightsLimitedSince
                state.flights.loading = false
                state.flightsNote = "adsb.lol is busy, next update in ${flightsBackoffS} s"
                state.flights.error = if (at - flightsLimitedSince > 15 * 60_000L)
                    "Flights: adsb.lol has been refusing requests for 15 minutes (HTTP 429)" else null
                return@run
            }
            flightsBackoffS = 0
            flightsLimitedSince = 0L
            state.flightsNote = null
            settle(state.flights, map(out) { it.flights to it.skipped }, "flights") { list ->
                flightsAt = at
                rememberTrail(list, at)
                list.map { flightMarker(it, at, at) }
            }
        }
    }

    private fun loadEvents() {
        if (!state.layers.events) return clear(state.events, "events")
        state.events.loading = true
        state.events.attemptAt = System.currentTimeMillis()
        run("events") {
            val out = withContext(Dispatchers.IO) { Feeds.events() }
            settle(state.events, map(out) { it.events to it.skipped }, "events") { list -> list.map(::eventMarker) }
        }
    }

    private fun loadSatellites() {
        val l = state.layers
        if (!l.satellites) {
            globe?.setPath(null)
            return clear(state.sats, "sats")
        }
        state.sats.loading = true
        state.sats.attemptAt = System.currentTimeMillis()
        run("sats") {
            val (result, problem) = withContext(Dispatchers.IO) { Feeds.satellites(cacheDir, l.satGroup.slug) }
            val sats = withContext(Dispatchers.Default) { result?.tles?.map(::Sgp4) ?: emptyList() }
            state.sats.loading = false
            state.sats.error = problem
            if (result != null) {
                state.sats.items = sats
                state.sats.skipped = result.unreadable
                state.satsDeep = result.deepSpace
                state.sats.updatedAt = System.currentTimeMillis()
                updateSatellites(System.currentTimeMillis(), orbit = true)
                refreshSelection()
            }
        }
    }

    /** Satellites move: positions recomputed every second while shown, orbit line every 30 s. */
    private fun updateSatellites(now: Long, orbit: Boolean) {
        val stations = state.layers.satGroup.slug == "stations"
        val markers = ArrayList<Marker>(state.sats.items.size)
        val shown = (if (state.layers.satellites) state.sats.items else emptyList()) +
            state.extraSats.filter { x -> state.sats.items.none { it.tle.norad == x.tle.norad } }
        for (s in shown) {
            val p = s.ecefAt(now) ?: continue // decayed
            val ll = Geo.latLon(p)
            val big = stations && (s.tle.norad == ISS || s.tle.norad == CSS)
            markers.add(
                Marker(
                    key = "s:" + s.tle.norad, lat = ll[0], lon = ll[1],
                    sizePx = (if (big) 20f else 12f) * density, rgb = Palette.satellite.toArgb(),
                    shape = Marker.SHAPE_SAT, altM = p.len() - EARTH_R,
                )
            )
        }
        globe?.setLayer("sats", markers)
        val sel = state.selected
        if (sel is Sel.OfSat && orbit) globe?.setPath(orbitPath(sel.s, now))
    }

    /** One full orbit from now, a point a minute. */
    private fun orbitPath(s: Sgp4, now: Long): List<V3> {
        val out = ArrayList<V3>()
        for (i in 0..(s.tle.periodMin.toInt() + 1)) s.ecefAt(now + i * 60_000L)?.let(out::add)
        return out
    }

    private fun <A, B> map(o: Net.Outcome<A>, f: (A) -> B): Net.Outcome<B> = when (o) {
        is Net.Outcome.Ok -> Net.Outcome.Ok(f(o.value))
        is Net.Outcome.Failed -> o
    }

    // ---- Markers ------------------------------------------------------------------------

    private val density get() = resources.displayMetrics.density

    /** Size is magnitude, colour is depth; the same scale as the legend. */
    private fun quakeMarker(q: Quake): Marker {
        val size = (4.0 + (q.mag ?: 0.0) * 2.4).coerceIn(4.0, 24.0).toFloat() * density
        return Marker("q:" + q.id, q.lat, q.lon, size, Palette.depth(q.depthKm).toArgb())
    }

    /** Aircraft shrink as you zoom out, so a busy sky reads as traffic, not a pile of arrows. */
    private fun planeSize(): Float {
        val alt = globe?.center()?.get(2) ?: 1_000_000.0
        val k = (1_200_000.0 / alt).coerceIn(0.45, 1.0)
        return (20.0 * k).toFloat() * density
    }

    private fun flightMarker(f: Flight, fetchedAt: Long, now: Long): Marker {
        val p = glide(f, fetchedAt, now)
        return Marker(
            "f:" + f.hex, p[0], p[1], planeSize(),
            (if (f.onGround) Palette.dim else Palette.flight).toArgb(),
            shape = Marker.SHAPE_PLANE, bearing = f.track ?: 0.0,
        )
    }

    // ---- Flights: gliding, trails, follow ----------------------------------------------

    private var flightsAt = 0L
    /** Seconds to wait after adsb.lol said "slow down" (0: not limited). */
    private var flightsBackoffS = 0
    private var flightsLimitedSince = 0L
    /** Recent positions per aircraft: lat, lon, altitude m, time. */
    private val trails = HashMap<String, ArrayDeque<DoubleArray>>()

    /** Between the 10 s updates each aircraft moves on along its track at its speed. */
    private fun glide(f: Flight, fetchedAt: Long, now: Long): DoubleArray {
        val track = f.track
        val speed = f.speedKt
        if (f.onGround || track == null || speed == null) return doubleArrayOf(f.lat, f.lon)
        val s = ((now - fetchedAt) / 1000.0).coerceIn(0.0, 30.0) // never run away if updates stop
        return Motion.ahead(f.lat, f.lon, track, speed, s)
    }

    private fun flightAt(hex: String, now: Long): DoubleArray? =
        state.flights.items.firstOrNull { it.hex == hex }?.let { glide(it, flightsAt, now) }

    private fun rememberTrail(list: List<Flight>, at: Long) {
        for (f in list) {
            val q = trails.getOrPut(f.hex) { ArrayDeque() }
            q.addLast(doubleArrayOf(f.lat, f.lon, (f.altFt ?: 0) * 0.3048, at.toDouble()))
        }
        // 30 minutes of history; aircraft gone for 5 minutes are forgotten.
        val cut = at - 30 * 60_000.0
        val iter = trails.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            while (e.value.isNotEmpty() && e.value.first()[3] < cut) e.value.removeFirst()
            if (e.value.isEmpty() || e.value.last()[3] < at - 5 * 60_000.0) iter.remove()
        }
    }

    /** Once a second: aircraft glide on, trails follow them, the camera follows its plane. */
    private fun glideFlights(now: Long) {
        val list = state.flights.items
        globe?.setLayer("flights", list.map { flightMarker(it, flightsAt, now) })
        if (state.layers.trails) {
            val lines = ArrayList<GlobeLine>()
            for (f in list) {
                val q = trails[f.hex] ?: continue
                val pts = q.map { Geo.ecef(it[0], it[1], it[2]) }.toMutableList()
                val p = glide(f, flightsAt, now)
                pts.add(Geo.ecef(p[0], p[1], (f.altFt ?: 0) * 0.3048))
                if (pts.size >= 2) lines.add(GlobeLine(pts, Palette.flight.toArgb(), if (f.hex == state.following) 0.95f else 0.45f))
            }
            globe?.setLines("trails", lines)
        } else {
            globe?.setLines("trails", emptyList())
        }
        state.following?.let { hex ->
            val p = flightAt(hex, now)
            if (p == null) state.following = null else globe?.lookAt(p[0], p[1])
        }
    }

    // ---- Cameras (OpenStreetMap) ---------------------------------------------------------

    private var camerasCentre: DoubleArray? = null

    /** Moved far enough since the last load that the loaded box no longer covers the view. */
    private fun camerasStale(): Boolean {
        val c = globe?.center() ?: return false
        val last = camerasCentre ?: return true
        val moved = Geo.toDeg(Geo.angle(Geo.ecef(c[0], c[1]), Geo.ecef(last[0], last[1]))) * 111_000
        return moved > last[2] * 0.4 || c[2] < last[2] * 0.5 || c[2] > last[2] * 2
    }

    private fun loadCameras() {
        if (!state.layers.cameras) {
            state.camerasNote = null
            camerasCentre = null
            return clear(state.cameras, "cameras")
        }
        val c = globe?.center() ?: return
        state.cameras.attemptAt = System.currentTimeMillis()
        if (c[2] > CAMERAS_MAX_ALT) {
            state.camerasNote = "zoom in below ${(CAMERAS_MAX_ALT / 1000).toInt()} km to load them"
            camerasCentre = null
            return
        }
        state.camerasNote = null
        val r = c[2] * 1.6 // metres around the centre: a little more than the screen
        val dLat = r / 111_000.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians(c[0])).coerceAtLeast(0.1)
        state.cameras.loading = true
        run("cameras") {
            val out = withContext(Dispatchers.IO) { Feeds.cameras(c[0] - dLat, c[1] - dLon, c[0] + dLat, c[1] + dLon) }
            if (out is Net.Outcome.Ok) camerasCentre = doubleArrayOf(c[0], c[1], r)
            settle(state.cameras, map(out) { it to 0 }, "cameras") { list ->
                list.map { cam ->
                    Marker("c:" + cam.id, cam.lat, cam.lon, 11f * density, (if (cam.alpr) Palette.alpr else Palette.camera).toArgb())
                }
            }
        }
    }

    // ---- Conflicts (GDELT) ----------------------------------------------------------------

    private fun loadConflicts() {
        if (!state.layers.conflicts) return clear(state.conflicts, "conflicts", "x:")
        state.conflicts.loading = true
        state.conflicts.attemptAt = System.currentTimeMillis()
        run("conflicts") {
            val out = withContext(Dispatchers.IO) { Feeds.conflicts() }
            settle(state.conflicts, map(out) { it to 0 }, "conflicts") { list ->
                list.map { c ->
                    // Bigger where more of the news is about it.
                    val size = (8.0 + 3.0 * kotlin.math.ln(c.count.coerceAtLeast(1).toDouble()) / kotlin.math.ln(2.0)).coerceIn(8.0, 22.0)
                    Marker(c.key, c.lat, c.lon, size.toFloat() * density, Palette.conflict.toArgb())
                }
            }
        }
    }

    // ---- Bus lines (OpenStreetMap) -------------------------------------------------------

    private val busArea = Area()

    private fun loadBusLines(force: Boolean = false) {
        if (!state.layers.busLines) {
            state.busLinesNote = null
            busArea.centre = null
            state.busStops = emptyList()
            stopsShown = false
            globe?.setLayer("busStops", emptyList())
            clear(state.busLines, "busLines", "bl:")
            if (state.selected?.key?.startsWith("bs:") == true) select(null)
            drawBusLines()
            return
        }
        val c = globe?.center() ?: return
        state.busLines.attemptAt = System.currentTimeMillis()
        if (force) busArea.centre = null
        if (c[2] > BUS_LINES_MAX_ALT) {
            state.busLinesNote = "zoom in below ${(BUS_LINES_MAX_ALT / 1000).toInt()} km to load them"
            busArea.centre = null
            return
        }
        state.busLinesNote = null
        val r = (c[2] * 1.6).coerceIn(3_000.0, 25_000.0) // metres around the centre; a city at most
        val b = box(c, r, 1.0)
        state.busLines.loading = true
        run("busLines") {
            val out = withContext(Dispatchers.IO) { Feeds.busLines(b[0], b[1], b[2], b[3]) }
            state.busLines.loading = false
            when (out) {
                is Net.Outcome.Ok -> {
                    busArea.centre = doubleArrayOf(c[0], c[1], r)
                    state.busLines.items = out.value.lines.sortedBy { it.short.padStart(6, '0') } // L2 before L10
                    state.busStops = out.value.stops
                    state.busLines.updatedAt = System.currentTimeMillis()
                    state.busLines.error = null
                    stopsShown = false
                    showBusStops()
                    drawBusLines()
                    refreshSelection()
                }
                is Net.Outcome.Failed -> state.busLines.error = out.message
            }
        }
    }

    /** The routes on the globe; the selected one bright, the rest quieter while one is selected. */
    private fun drawBusLines() {
        val sel = (state.selected as? Sel.OfBusLine)?.l?.id
        if (!state.layers.busLines) {
            globe?.setLines("busLines", emptyList())
            return
        }
        val lines = state.busLines.items.map { l ->
            val pts = ArrayList<V3>()
            for (seg in l.paths) for (i in 0 until seg.size - 1) {
                pts.add(Geo.ecef(seg[i][0], seg[i][1], BUS_LINE_LIFT_M))
                pts.add(Geo.ecef(seg[i + 1][0], seg[i + 1][1], BUS_LINE_LIFT_M))
            }
            val alpha = when {
                sel == null -> 0.8f
                l.id == sel -> 1f
                else -> 0.25f
            }
            GlobeLine(pts, busColour(l), alpha, pairs = true)
        }
        // The selected line last, so it is drawn over the others.
        globe?.setLines("busLines", lines.sortedBy { if (it.alpha == 1f) 1 else 0 })
    }

    private fun busColour(l: BusLine) = l.colour?.let { 0xFF000000.toInt() or it } ?: Palette.bus.toArgb()

    private var stopsShown = false

    /** Stops only close in: a city's worth of dots from higher up is noise. */
    private fun showBusStops() {
        val alt = globe?.center()?.get(2) ?: return
        val want = state.layers.busLines && alt <= BUS_STOPS_MAX_ALT
        if (want == stopsShown) return
        stopsShown = want
        globe?.setLayer("busStops", if (!want) emptyList() else state.busStops.map { s ->
            Marker("bs:" + s.id, s.lat, s.lon, 9f * density, Palette.busStop.toArgb())
        })
    }

    /** Where to fly for a bus thing, or null when it is not one. */
    private fun busPlace(sel: Sel): DoubleArray? = when (sel) {
        is Sel.OfBusStop -> doubleArrayOf(sel.s.lat, sel.s.lon)
        is Sel.OfBus -> doubleArrayOf(sel.b.lat, sel.b.lon)
        is Sel.OfBusLine -> {
            val all = sel.l.paths.flatten()
            if (all.isEmpty()) null else all[all.size / 2]
        }
        else -> null
    }

    // ---- Live buses (GTFS Realtime through Transitland) ----------------------------------

    private var busFeedsAt: DoubleArray? = null
    private val busWays = HashMap<String, Feeds.RtWay>()
    private var busesBackoffS = 0

    private fun loadBuses(force: Boolean = false) {
        if (!state.layers.buses) {
            state.busesNote = null
            state.busFeeds = emptyList(); state.busFeedsUnreadable = emptyList()
            busFeedsAt = null
            return clear(state.buses, "buses", "bv:")
        }
        val key = state.keys.transitland
        if (key.isEmpty()) { state.busesNote = "add your Transitland key in the menu"; return }
        val c = globe?.center() ?: return
        state.buses.attemptAt = System.currentTimeMillis()
        if (c[2] > BUSES_MAX_ALT) {
            state.busesNote = "zoom in below ${(BUSES_MAX_ALT / 1000).toInt()} km to load them"
            return
        }
        if (force) { busFeedsAt = null; busWays.clear() }
        val last = busFeedsAt
        val rediscover = last == null ||
            Geo.toDeg(Geo.angle(Geo.ecef(c[0], c[1]), Geo.ecef(last[0], last[1]))) * 111_000 > BUS_FEEDS_MOVE_M
        state.buses.loading = true
        run("buses") {
            if (rediscover) {
                when (val f = withContext(Dispatchers.IO) { Feeds.busFeeds(key, c[0], c[1], BUS_FEEDS_RADIUS_M) }) {
                    is Net.Outcome.Ok -> {
                        state.busFeeds = f.value
                        busFeedsAt = doubleArrayOf(c[0], c[1])
                    }
                    is Net.Outcome.Failed -> {
                        state.buses.loading = false
                        state.buses.error = f.message
                        busesBackoffS = f.retryAfterS ?: if (f.code == 429) 300 else 0
                        return@run
                    }
                }
            }
            val feeds = state.busFeeds
            if (feeds.isEmpty()) {
                state.buses.loading = false
                state.buses.error = null
                state.busesNote = "no operator here publishes live positions"
                state.buses.items = emptyList()
                globe?.setLayer("buses", emptyList())
                return@run
            }
            val out = withContext(Dispatchers.IO) { Feeds.buses(key, feeds, busWays) }
            busesBackoffS = (out as? Net.Outcome.Failed)?.let { it.retryAfterS ?: if (it.code == 429) 300 else 0 } ?: 0
            if (out is Net.Outcome.Ok) {
                state.busFeedsUnreadable = out.value.second
                state.busesNote = if (out.value.first.isEmpty() && out.value.second.isNotEmpty())
                    "${out.value.second.joinToString()} needs its own key: not shown" else null
            }
            val centre = Geo.ecef(c[0], c[1])
            settle(state.buses, map(out) { v ->
                // A big city runs thousands: the nearest are the ones on screen.
                v.first.sortedBy { Geo.angle(Geo.ecef(it.lat, it.lon), centre) }.take(BUSES_MAX) to 0
            }, "buses") { list ->
                list.map { b ->
                    Marker("bv:" + b.key, b.lat, b.lon, 17f * density, Palette.bus.toArgb(),
                        shape = if (b.bearing != null) Marker.SHAPE_PLANE else Marker.SHAPE_DOT, bearing = b.bearing ?: Double.NaN)
                }
            }
        }
    }

    // ---- Layers that need a personal key --------------------------------------------------

    /** Remembers where a box-shaped layer last loaded, to know when the view has left it. */
    private class Area {
        var centre: DoubleArray? = null // lat, lon, radius m
        fun stale(c: DoubleArray?): Boolean {
            c ?: return false
            val last = centre ?: return true
            val moved = Geo.toDeg(Geo.angle(Geo.ecef(c[0], c[1]), Geo.ecef(last[0], last[1]))) * 111_000
            return moved > last[2] * 0.4 || c[2] * 1.5 < last[2] * 0.5 || c[2] * 1.5 > last[2] * 2
        }
    }

    /** South, west, north, east around the screen centre, [radiusM] out, at most [maxSpanDeg] across. */
    private fun box(c: DoubleArray, radiusM: Double, maxSpanDeg: Double): DoubleArray {
        val dLat = (radiusM / 111_000.0).coerceAtMost(maxSpanDeg / 2)
        val dLon = (dLat / kotlin.math.cos(Math.toRadians(c[0])).coerceAtLeast(0.1)).coerceAtMost(maxSpanDeg / 2)
        return doubleArrayOf((c[0] - dLat).coerceAtLeast(-89.0), c[1] - dLon, (c[0] + dLat).coerceAtMost(89.0), c[1] + dLon)
    }

    // Ships: a live stream for the box around the view.
    private val ais = AisStream(
        // Busy waters send hundreds of messages a second: collect here, publish once a second.
        onMessage = { m ->
            when (m) {
                is Ais.Msg.Position -> shipMap[m.ship.mmsi] = m.ship.copy(name = m.ship.name ?: shipMap[m.ship.mmsi]?.name ?: shipNames[m.ship.mmsi])
                is Ais.Msg.Name -> {
                    shipNames[m.mmsi] = m.name
                    shipMap[m.mmsi]?.let { shipMap[m.mmsi] = it.copy(name = m.name) }
                }
                else -> {}
            }
        },
        onProblem = { p ->
            state.shipsProblem = p
            if (p != null) shipsOpen = false // the ticker reopens after a pause
        },
    )
    private val shipMap = HashMap<Long, Ship>()
    private val shipNames = HashMap<Long, String>()
    private var shipsOpen = false
    private var shipsOpenedAt = 0L
    private val shipArea = Area()

    private fun closeShips() {
        ais.close()
        shipsOpen = false
        shipArea.centre = null
        shipMap.clear()
        state.ships = emptyMap()
        state.shipsProblem = null
        state.shipsNote = null
        globe?.setLayer("ships", emptyList())
        if (state.selected is Sel.OfShip) select(null)
    }

    private fun tickShips(now: Long) {
        val key = state.keys.ais
        val c = globe?.center() ?: return
        when {
            key.isEmpty() -> { state.shipsNote = "add your AISStream key in the menu"; return }
            c[2] > SHIPS_MAX_ALT -> {
                state.shipsNote = "zoom in below ${(SHIPS_MAX_ALT / 1000).toInt()} km to load them"
                if (shipsOpen) { ais.close(); shipsOpen = false; shipArea.centre = null }
            }
            (!shipsOpen || shipArea.stale(c)) && now - shipsOpenedAt > 20_000 -> {
                state.shipsNote = null
                val r = c[2] * 1.5
                val b = box(c, r, 12.0)
                ais.open(key, b[0], b[1], b[2], b[3])
                shipsOpen = true
                shipsOpenedAt = now
                shipArea.centre = doubleArrayOf(c[0], c[1], r)
            }
        }
        // Forget ships silent for 20 minutes; glide the rest along their course.
        shipMap.values.removeAll { now - it.atMs >= 20 * 60_000L }
        val fresh = HashMap(shipMap)
        state.ships = fresh
        globe?.setLayer("ships", fresh.values.sortedByDescending { it.atMs }.take(4000).map { sh ->
            val p = if (sh.sogKt != null && sh.sogKt > 0.5 && sh.cog != null)
                Motion.ahead(sh.lat, sh.lon, sh.cog, sh.sogKt, ((now - sh.atMs) / 1000.0).coerceAtMost(600.0))
            else doubleArrayOf(sh.lat, sh.lon)
            Marker("v:" + sh.mmsi, p[0], p[1], 15f * density, Palette.ship.toArgb(),
                shape = if (sh.bearing != null) Marker.SHAPE_PLANE else Marker.SHAPE_DOT, bearing = sh.bearing ?: Double.NaN)
        })
    }

    // Webcams near the screen centre.
    private val webcamArea = Area()

    private fun loadWebcams(force: Boolean = false) {
        if (!state.layers.webcams) { state.webcamsNote = null; webcamArea.centre = null; return clear(state.webcams, "webcams") }
        val key = state.keys.windy
        if (key.isEmpty()) { state.webcamsNote = "add your Windy key in the menu"; return }
        val c = globe?.center() ?: return
        val now = System.currentTimeMillis()
        state.webcams.attemptAt = now
        state.webcamsNote = null
        if (force) { webcamArea.centre = null; worldWebcams = null }
        if (c[2] > WEBCAMS_MAX_ALT) {
            // From high up: the most popular webcams on Earth. Image links expire after 15 min.
            webcamArea.centre = null
            val cached = worldWebcams
            if (cached != null && now - worldWebcamsAt < WORLD_WEBCAMS_MS) {
                if (!state.webcamsWorld) {
                    state.webcamsWorld = true
                    settle(state.webcams, Net.Outcome.Ok(cached to 0), "webcams", ::webcamMarkers)
                }
                return
            }
            state.webcams.loading = true
            run("webcams") {
                val out = withContext(Dispatchers.IO) { Feeds.topWebcams(key, WORLD_WEBCAM_PAGES) }
                if (out is Net.Outcome.Ok) {
                    worldWebcams = out.value; worldWebcamsAt = System.currentTimeMillis()
                    state.webcamsWorld = true
                }
                settle(state.webcams, map(out) { it to 0 }, "webcams", ::webcamMarkers)
            }
            return
        }
        val radiusKm = (c[2] * 1.5 / 1000).toInt().coerceIn(5, 250)
        state.webcams.loading = true
        run("webcams") {
            val out = withContext(Dispatchers.IO) { Feeds.webcams(key, c[0], c[1], radiusKm) }
            if (out is Net.Outcome.Ok) {
                webcamArea.centre = doubleArrayOf(c[0], c[1], radiusKm * 1000.0 / 1.5 * 1.5)
                state.webcamsWorld = false
            }
            settle(state.webcams, map(out) { it to 0 }, "webcams", ::webcamMarkers)
        }
    }

    private var worldWebcams: List<Webcam>? = null
    private var worldWebcamsAt = 0L

    private fun webcamMarkers(list: List<Webcam>) =
        list.map { Marker("w:" + it.id, it.lat, it.lon, 13f * density, Palette.webcam.toArgb(), shape = Marker.SHAPE_SAT) }

    // Fire hotspots (last 24 h) in the box around the view.
    private val fireArea = Area()

    private fun loadFires(force: Boolean = false) {
        if (!state.layers.fires) { state.firesNote = null; fireArea.centre = null; return clear(state.fires, "fires", "h:") }
        val key = state.keys.firms
        if (key.isEmpty()) { state.firesNote = "add your NASA FIRMS key in the menu"; return }
        val c = globe?.center() ?: return
        val now = System.currentTimeMillis()
        state.fires.attemptAt = now
        state.firesNote = null
        if (force) { fireArea.centre = null; worldFires = null }
        if (c[2] > FIRES_MAX_ALT) {
            // From high up: the strongest fires on Earth, one download kept for 30 minutes.
            fireArea.centre = null
            val cached = worldFires
            if (cached != null && now - worldFiresAt < WORLD_FIRES_MS) {
                if (!state.firesWorld) {
                    state.firesWorld = true
                    settle(state.fires, Net.Outcome.Ok(cached to 0), "fires", ::fireMarkers)
                }
                return
            }
            state.fires.loading = true
            run("fires") {
                val out = withContext(Dispatchers.IO) { map(Feeds.firesWorld(key)) { Firms.strongest(it, WORLD_FIRES) } }
                if (out is Net.Outcome.Ok) {
                    worldFires = out.value; worldFiresAt = System.currentTimeMillis()
                    state.firesWorld = true
                }
                settle(state.fires, map(out) { it to 0 }, "fires", ::fireMarkers)
            }
            return
        }
        val r = c[2] * 1.5
        val b = box(c, r, 30.0)
        state.fires.loading = true
        run("fires") {
            val out = withContext(Dispatchers.IO) { Feeds.fires(key, b[1], b[0], b[3], b[2]) }
            if (out is Net.Outcome.Ok) {
                fireArea.centre = doubleArrayOf(c[0], c[1], r)
                state.firesWorld = false
            }
            settle(state.fires, map(out) { it to 0 }, "fires", ::fireMarkers)
        }
    }

    private var worldFires: List<Hotspot>? = null
    private var worldFiresAt = 0L

    private fun fireMarkers(list: List<Hotspot>) = list.map { h ->
        val size = (7.0 + kotlin.math.sqrt(h.frpMw ?: 1.0) * 1.6).coerceIn(7.0, 20.0).toFloat() * density
        Marker(Sel.OfFire(h).key, h.lat, h.lon, size, Palette.fire.toArgb())
    }

    // ---- Search ---------------------------------------------------------------------------

    /** Places (OpenStreetMap), flights by callsign (adsb.lol) and satellites by name (CelesTrak). */
    private fun search(q: String) {
        val query = q.trim()
        val se = state.search
        se.query = q
        if (query.length < 2) return
        se.busy = true
        se.errors = emptyList()
        se.searched = true
        // What is already on the globe answers at once.
        val local = ArrayList<Hit>()
        state.flights.items.filter { it.callsign?.contains(query, true) == true || it.registration?.contains(query, true) == true }
            .take(5).forEach { local.add(Hit.OfFlight(it)) }
        (state.sats.items + state.extraSats).filter { it.tle.name.contains(query, true) }.take(5).forEach { local.add(Hit.OfSat(it)) }
        se.hits = local
        run("search") {
            val errors = ArrayList<String>()
            val found = ArrayList(local)
            val looksLikeCallsign = Regex("^[A-Za-z]{2,3}[0-9][A-Za-z0-9]{0,4}$").matches(query)
            if (looksLikeCallsign && local.none { it is Hit.OfFlight }) {
                when (val o = withContext(Dispatchers.IO) { Feeds.callsign(query) }) {
                    is Net.Outcome.Ok -> o.value.flights.forEach { found.add(Hit.OfFlight(it)) }
                    is Net.Outcome.Failed -> errors.add(o.message)
                }
            }
            if (query.length >= 3) {
                when (val o = withContext(Dispatchers.IO) { Feeds.satellitesNamed(query) }) {
                    is Net.Outcome.Ok -> o.value.tles.take(8).forEach { t ->
                        if (found.none { it is Hit.OfSat && it.s.tle.norad == t.norad }) found.add(Hit.OfSat(Sgp4(t)))
                    }
                    // CelesTrak answers "No GP data found" for no match: not worth a red line.
                    is Net.Outcome.Failed -> if ("no connection" in o.message || "HTTP" in o.message) errors.add(o.message)
                }
            }
            when (val o = withContext(Dispatchers.IO) { Feeds.places(query) }) {
                is Net.Outcome.Ok -> o.value.forEach { found.add(Hit.OfPlace(it)) }
                is Net.Outcome.Failed -> errors.add(o.message)
            }
            se.hits = found
            se.errors = errors
            se.busy = false
        }
    }

    /** A search hit was chosen: go there and open its card. */
    private fun pick(hit: Hit) {
        state.search.open = false
        when (hit) {
            is Hit.OfPlace -> {
                val sel = Sel.OfPlace(hit.p.lat, hit.p.lon)
                select(sel)
                globe?.setLayer("pin", listOf(Marker(sel.key, hit.p.lat, hit.p.lon, 16f * density, Palette.accent.toArgb())))
                globe?.flyTo(hit.p.lat, hit.p.lon, 30_000.0)
            }
            is Hit.OfFlight -> {
                val f = hit.f
                if (state.flights.items.none { it.hex == f.hex }) {
                    state.flights.items = state.flights.items + f
                    flightsAt = System.currentTimeMillis()
                }
                // Follow it: flights then load around it, wherever it is in the world.
                state.following = f.hex
                if (!state.layers.flights) change(state.layers.copy(flights = true))
                globe?.flyTo(f.lat, f.lon, 300_000.0)
                select(Sel.OfFlight(f))
                globe?.select("f:" + f.hex, fly = false)
                state.flights.attemptAt = 0 // reload around it right away
            }
            is Hit.OfSat -> {
                if ((state.sats.items + state.extraSats).none { it.tle.norad == hit.s.tle.norad }) {
                    state.extraSats = state.extraSats + hit.s
                }
                updateSatellites(System.currentTimeMillis(), orbit = false)
                select(Sel.OfSat(hit.s))
                globe?.select("s:" + hit.s.tle.norad, fly = true)
            }
        }
    }

    // ---- Map cache ------------------------------------------------------------------------

    private fun measureCache() {
        run("cache") {
            state.cacheBytes = withContext(Dispatchers.IO) {
                File(cacheDir, "tiles").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }
    }

    private fun clearCache() {
        run("cache") {
            withContext(Dispatchers.IO) {
                File(cacheDir, "tiles").deleteRecursively()
                runCatching { HttpResponseCache.getInstalled()?.delete() }
            }
            installHttpCache()
            state.cacheBytes = 0
        }
    }

    private fun eventMarker(e: NatEvent): Marker =
        Marker("e:" + e.id, e.lat, e.lon, 13f * density, Palette.event(e.category).toArgb())

    private fun meMarker(l: Location): Marker =
        Marker("me", l.latitude, l.longitude, 26f * density, Palette.me.toArgb(), shape = Marker.SHAPE_ME)

    // ---- Selection ----------------------------------------------------------------------

    private fun selectionFor(key: String): Sel? = when {
        key == "me" -> Sel.Me
        key.startsWith("p:") -> (state.selected as? Sel.OfPlace)?.takeIf { it.key == key }
        key.startsWith("q:") -> state.quakes.items.firstOrNull { "q:" + it.id == key }?.let { Sel.OfQuake(it) }
        key.startsWith("f:") -> state.flights.items.firstOrNull { "f:" + it.hex == key }?.let { Sel.OfFlight(it) }
        key.startsWith("s:") -> (state.sats.items + state.extraSats).firstOrNull { "s:" + it.tle.norad == key }?.let { Sel.OfSat(it) }
        key.startsWith("c:") -> state.cameras.items.firstOrNull { "c:" + it.id == key }?.let { Sel.OfCamera(it) }
        key.startsWith("v:") -> key.drop(2).toLongOrNull()?.let { state.ships[it] }?.let { Sel.OfShip(it) }
        key.startsWith("w:") -> state.webcams.items.firstOrNull { "w:" + it.id == key }?.let { Sel.OfWebcam(it) }
        key.startsWith("h:") -> state.fires.items.firstOrNull { Sel.OfFire(it).key == key }?.let { Sel.OfFire(it) }
        key.startsWith("e:") -> state.events.items.firstOrNull { "e:" + it.id == key }?.let { Sel.OfEvent(it) }
        key.startsWith("x:") -> state.conflicts.items.firstOrNull { it.key == key }?.let { Sel.OfConflict(it) }
        key.startsWith("bs:") -> state.busStops.firstOrNull { "bs:" + it.id == key }?.let { Sel.OfBusStop(it) }
        key.startsWith("bl:") -> state.busLines.items.firstOrNull { "bl:" + it.id == key }?.let { Sel.OfBusLine(it) }
        key.startsWith("bv:") -> state.buses.items.firstOrNull { "bv:" + it.key == key }?.let { Sel.OfBus(it) }
        else -> null
    }

    private fun select(sel: Sel?) {
        val lineChanged = (state.selected as? Sel.OfBusLine)?.l?.id != (sel as? Sel.OfBusLine)?.l?.id
        state.selected = sel
        if (lineChanged) drawBusLines()
        state.passes = null
        state.weather = null
        if (sel !is Sel.OfPlace) globe?.setLayer("pin", emptyList())
        if (sel is Sel.OfPlace || sel is Sel.Me) loadWeather(sel)
        globe?.setPath(if (sel is Sel.OfSat) orbitPath(sel.s, System.currentTimeMillis()) else null)
        recomputePasses()
    }

    /** After a refresh the card shows the new data for the same thing, or closes if it is gone. */
    private fun refreshSelection() {
        val sel = state.selected ?: return
        val fresh = selectionFor(sel.key)
        if (fresh == null) {
            select(null)
            globe?.select(null, fly = false)
        } else if (fresh !is Sel.OfSat) {
            state.selected = fresh
        }
    }

    private fun recomputePasses() {
        val sel = state.selected as? Sel.OfSat ?: return
        val me = state.me ?: return
        run("passes") {
            val p = withContext(Dispatchers.Default) {
                Sky.passes(sel.s, me.latitude, me.longitude, System.currentTimeMillis(), hours = 48.0)
            }
            if (state.selected?.key == sel.key) state.passes = p
        }
    }

    // ---- Location -----------------------------------------------------------------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() =
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    /** The location button: switches location on if needed, then flies there. */
    private fun myLocation(alt: Double = ME_ALT) {
        flyToMeAlt = alt
        if (!state.layers.location) {
            state.layers = state.layers.copy(location = true)
            store.save(state.layers)
        }
        val me = state.me
        if (me != null) {
            globe?.flyTo(me.latitude, me.longitude, alt)
            return
        }
        flyToMeOnFix = true
        if (hasLocationPermission()) where.start() else askPermission()
    }

    /** Tap on the red location line: ask again, or open the app's settings when Android won't ask. */
    private fun fixLocation() {
        when {
            hasLocationPermission() -> where.start()
            locationBlocked -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            else -> askPermission()
        }
    }

    // ---- Misc ---------------------------------------------------------------------------

    private fun installHttpCache() {
        if (HttpResponseCache.getInstalled() != null) return
        try {
            HttpResponseCache.install(File(cacheDir, "http"), 20L * 1024 * 1024) // feeds only; tiles have their own cache
        } catch (e: IOException) {
            // Works without a cache, only slower on revisits.
            Log.w("SonderEye", "HTTP cache unavailable", e)
        }
    }

    override fun onResume() {
        super.onResume()
        globe?.onResume()
    }

    override fun onPause() {
        globe?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ais.close()
        globe?.release()
        HttpResponseCache.getInstalled()?.flush()
        super.onDestroy()
    }

    companion object {
        private const val QUAKES_AUTO_MS = 5 * 60_000L
        /** adsb.lol is a free community service: one area request every 15 s (planes glide in between). */
        private const val FLIGHTS_MS = 15_000L
        /** RainViewer publishes a frame every 10 minutes. */
        private const val RADAR_MS = 10 * 60_000L
        /** The last hour, looped. */
        private const val RADAR_FRAMES = 6
        /** Live AIS for a box: kept to a region so the phone is not flooded. */
        private const val SHIPS_MAX_ALT = 2_000_000.0
        private const val WEBCAMS_MAX_ALT = 1_000_000.0
        private const val FIRES_MAX_ALT = 6_000_000.0
        /** Bus routes come from Overpass in city-sized boxes; stops only at street scale. */
        private const val BUS_LINES_MAX_ALT = 40_000.0
        private const val BUS_STOPS_MAX_ALT = 12_000.0
        private const val BUS_LINE_LIFT_M = 4.0 // just above the ground, never under it
        private const val BUS_FLY_ALT = 8_000.0
        private const val BUSES_MAX_ALT = 300_000.0
        private const val BUSES_MS = 30_000L
        /** GDELT updates every 15 minutes. */
        private const val CONFLICTS_MS = 15 * 60_000L
        private const val BUSES_MAX = 3_000
        private const val BUS_FEEDS_RADIUS_M = 10_000
        private const val BUS_FEEDS_MOVE_M = 30_000.0
        /** Above the limits: the whole Earth, cut to what reads from space. */
        private const val WORLD_FIRES = 10_000
        private const val WORLD_FIRES_MS = 30 * 60_000L
        private const val WORLD_WEBCAM_PAGES = 4 // 200 webcams
        private const val WORLD_WEBCAMS_MS = 12 * 60_000L // image links last 15 min
        /** Overpass boxes stay small: cameras load only below this height. */
        private const val CAMERAS_MAX_ALT = 60_000.0
        /** Camera height when flying to you: a city and its surroundings. */
        private const val ME_ALT = 25_000.0
        /** Long-press on the pin: your street. */
        private const val ME_CLOSE_ALT = 600.0
        const val ISS = 25544
        const val CSS = 48274 // Tiangong
    }
}
