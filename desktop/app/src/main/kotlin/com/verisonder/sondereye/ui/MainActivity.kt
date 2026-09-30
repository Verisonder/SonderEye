package com.verisonder.sondereye.ui

import com.verisonder.sondereye.data.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
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
import com.verisonder.sondereye.globe.RoadSet
import com.verisonder.sondereye.globe.GlobeStatus
import com.verisonder.sondereye.globe.GlobeView
import com.verisonder.sondereye.globe.Marker
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
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
    /** Where the weather is for ("Tangier, Morocco"). */
    var place by mutableStateOf<String?>(null)
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
    /** The whole world's fires and webcams, for their lists wherever the view is. */
    var worldFires by mutableStateOf<List<Hotspot>?>(null)
    var worldWebcams by mutableStateOf<List<Webcam>?>(null)
    /** The list whose worldwide set is downloading, and why the last one failed. */
    var worldLoading by mutableStateOf<String?>(null)
    var worldError by mutableStateOf<String?>(null)
    /** Places in the news for fighting, last 24 h (GDELT). */
    val conflicts = Feed<Conflict>()
    /** How many hours of GDELT's event files the conflict pins cover (they build up to a day). */
    var conflictsHours by mutableStateOf(0)
    /** Bus lines (one per direction) and their stops, OpenStreetMap. */
    val busLines = Feed<BusLine>()
    var busStops by mutableStateOf<List<BusStop>>(emptyList())
    var busLinesNote by mutableStateOf<String?>(null)
    /** Why the street-level roads could not load, if they could not. */
    var roadsProblem by mutableStateOf<String?>(null)
    /** The bus line being ridden, and where the rider is along it. */
    var riding by mutableStateOf<BusLine?>(null)
    var ride by mutableStateOf<com.verisonder.sondereye.core.Ride.Progress?>(null)
    /** Speed from the last position fix, m/s (for the time to the next stop). */
    var rideSpeed by mutableStateOf<Float?>(null)
    /** Gemini's account of conflict places, by pin; the one being written; the last failure. */
    var explained by mutableStateOf<Map<String, String>>(emptyMap())
    var explaining by mutableStateOf<String?>(null)
    var explainProblem by mutableStateOf<Pair<String, String>?>(null)
    /** The start-up sequence is on screen. */
    var booting by mutableStateOf(false)
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

/**
 * The app's logic: the phone's MainActivity, the same code, run by the window (Main.kt)
 * instead of by Android. [start] is onCreate; [Content] is what setContent showed.
 */
class MainActivity(private val scope: CoroutineScope, private val screenDensity: Float) {
    private val cacheDir = AppDirs.cache
    private val filesDir = AppDirs.files

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

    /** What onCreate did; [fresh]: a new start (the start-up sequence plays). */
    fun start(fresh: Boolean) {
        val savedInstanceState: Any? = if (fresh) null else Unit
        store = Settings()
        state.layers = store.load()
        state.keys = store.keys()
        state.brief.prefs = store.brief()
        Palette.dark = !state.layers.lightPanels
        Palette.dotColors = state.layers.dotColors
        where = Where(
            onFix = { loc ->
                val first = state.me == null
                state.me = loc
                state.meProblem = null
                if (first) {
                    store.saveHome(loc.latitude, loc.longitude)
                    if (state.layers.passAlerts) schedulePassAlerts()
                }
                globe?.setLayer("me", listOf(meMarker(loc)))
                updateRide(loc)
                if (flyToMeOnFix) {
                    flyToMeOnFix = false
                    globe?.flyTo(loc.latitude, loc.longitude, flyToMeAlt)
                }
                if (state.passes == null) recomputePasses()
            },
            onProblem = { state.meProblem = it },
        )

        run {
            globe = GlobeView(cacheDir, screenDensity, object : GlobeView.Listener {
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
            applyHidden()
            globe?.northLocked = state.layers.northLock
            // A fresh start (not a rotation): the start-up sequence, and the globe flying in behind it.
            if (savedInstanceState == null && state.layers.bootAnimation) {
                state.booting = true
                globe?.intro(delayMs = BOOT_FLY_DELAY_MS, flyMs = BOOT_FLY_MS)
            }
        }

        refreshAll()
        if (state.layers.passAlerts) schedulePassAlerts()
        restoreBrief()
        loops()
    }

    /** What setContent showed on the phone. */
    @Composable
    fun Content() {
        run {
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
                        revealGlobe = { globe?.reveal() },
                        ride = ::ride,
                        explain = ::explain,
                        myLocation = { myLocation() },
                        myLocationClose = { myLocation(ME_CLOSE_ALT) },
                        fixLocation = ::fixLocation,
                        sky = {},
                        setMyPlace = { lat, lon ->
                            where.setMine(lat, lon)
                            if (!state.layers.location) change(state.layers.copy(location = true))
                            select(Sel.Me)
                        },
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
                            if (layer == "fires" || layer == "webcams") loadWorldFor(layer)
                            if (layer != null) {
                                state.layersOpen = false; state.search.open = false; state.brief.open = false
                            }
                        },
                        goTo = { sel ->
                            state.listLayer = null
                            select(sel)
                            val close = closePlace(sel)
                            globe?.select(sel.key, fly = close == null)
                            close?.let { (p, alt) -> globe?.flyTo(p[0], p[1], alt) }
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
    }

    private fun loops() {

        // Position readout: four times a second, and only when it changed.
        scope.launch {
            run {
                while (true) {
                    val c = globe?.center()
                    if (c != null && !c.contentEquals(state.view)) state.view = c
                    delay(250)
                }
            }
        }

        // Radar animation: the past hour's frames in a loop, holding on the latest.
        scope.launch {
            run {
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
        scope.launch {
            run {
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
                        if (roadsWanted() && !roadsLoading && (state.roadsProblem == null || now - roadsAttemptAt >= 15_000) && roadsStale()) loadRoads()
                        if (l.busLines) {
                            // Straight away when the view needs them; the 15 s pause is only for retrying a failure.
                            if (!state.busLines.loading && (state.busLines.error == null || now - state.busLines.attemptAt >= 15_000) && busLinesStale()) loadBusLines()
                            showBusStops()
                        }
                        // Every 15 min; while GDELT is failing, every 3.
                        if (l.conflicts && !state.conflicts.loading &&
                            now - state.conflicts.attemptAt >= (if (state.conflicts.error != null) 3 * 60_000L else CONFLICTS_MS)) loadConflicts()
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
            new.dayNight != old.dayNight || new.lights != old.lights || new.pinScale != old.pinScale
        ) applyMap()
        if (new.trails != old.trails) glideFlights(System.currentTimeMillis())
        if (new.cameras != old.cameras) loadCameras()
        if (new.busLines != old.busLines) loadBusLines(force = true)
        if (new.hidden != old.hidden) applyHidden()
        if (new.buses != old.buses) loadBuses(force = true)
        if (new.conflicts != old.conflicts) loadConflicts()
        Palette.dark = !new.lightPanels
        Palette.dotColors = new.dotColors
        if (new.dotColors != old.dotColors || new.dotSizes != old.dotSizes) globe?.setDotStyles(new.dotColors, new.dotSizes)
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
        // Where you are, fresh: a new fix now rather than the last one kept.
        if (state.layers.location && hasLocationPermission()) where.refresh()
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
        if (l.map != MapStyle.STREETS && l.roads) { overlays.add(TileSource.ROADS); overlays.add(TileSource.ROAD_NAMES) }
        if (!roadsWanted()) {
            roadArea.centre = null
            state.roadsProblem = null
            globe?.setRoads(null, 0.0)
        }
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
        globe?.pinScale = l.pinScale
        globe?.setDotStyles(l.dotColors, l.dotSizes)
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
        if (state.layers.cameras || state.layers.busLines || roadsWanted() || state.search.searched) {
            all.add("© OpenStreetMap contributors"); required.add("© OpenStreetMap contributors")
        }
        if (roadsWanted()) all.add("Street roads: OpenFreeMap, © OpenMapTiles")
        state.allCredits = all.distinct()
        // Always shown: Esri, OpenStreetMap and RainViewer require it wherever their data is.
        state.credits = required.distinct()
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
                b.weatherProblem = "Weather: your location is not known yet. Click the pin key once, or right-click your place on the map and set it."
            } else {
                when (val w = withContext(Dispatchers.IO) { Feeds.forecast(here[0], here[1]) }) {
                    is Net.Outcome.Ok -> { b.weather = w.value.first; b.forecast = w.value.second; b.weatherProblem = null; b.weatherAt = System.currentTimeMillis() }
                    is Net.Outcome.Failed -> b.weatherProblem = w.message
                }
                // The town's name; without it the panel still says where by coordinates.
                b.place = (withContext(Dispatchers.IO) { Feeds.town(here[0], here[1]) } as? Net.Outcome.Ok)?.value
                    ?: "%.3f, %.3f".format(java.util.Locale.ROOT, here[0], here[1])
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
                if (News.sourcesFor(p).isEmpty()) problems.add("News: no sources chosen. Click Customise.")
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
                when (val out = withContext(Dispatchers.IO) { Feeds.brief(key, b.stories, weatherLine, b.place, p) }) {
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
        schedulePassAlerts()
        if (store.home() == null && state.me == null) myLocation()
    }

    private fun schedulePassAlerts() {
        PassAlerts.ensureChannel(this)
        PassAlerts.schedule(this) { problem -> javax.swing.SwingUtilities.invokeLater { state.alertProblem = problem } }
    }

    // ---- Layers -------------------------------------------------------------------------

    /** Runs [block] as the only job named [name]; a newer call replaces an older one. */
    private fun run(name: String, block: suspend () -> Unit) {
        jobs[name]?.cancel()
        jobs[name] = scope.launch { block() }
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

    private val density get() = screenDensity

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

    // ---- Street roads (OpenStreetMap, drawn by the app) -----------------------------------

    private val roadArea = Area()
    private var roadsLoading = false
    private var roadsAttemptAt = 0L

    private fun roadsWanted() = state.layers.roads && state.layers.map != MapStyle.STREETS

    /** Like the bus lines: a loaded box serves every closer view inside it. */
    private fun roadsStale(): Boolean {
        val c = globe?.center() ?: return false
        if (c[2] > ROADS_VECTOR_ALT) return false
        val last = roadArea.centre ?: return true
        val moved = Geo.toDeg(Geo.angle(Geo.ecef(c[0], c[1]), Geo.ecef(last[0], last[1]))) * 111_000
        return moved > last[2] * 0.5 || roadsRadius(c[2]) > last[2] * 1.25
    }

    private fun roadsRadius(alt: Double) = (alt * 1.4).coerceIn(1_500.0, 3_500.0)

    private fun loadRoads() {
        val c = globe?.center() ?: return
        roadsAttemptAt = System.currentTimeMillis()
        roadsLoading = true
        val r = roadsRadius(c[2])
        val b = box(c, r, 1.0)
        run("roads") {
            // The vector tiles covering the view, all at once.
            val tiles = com.verisonder.sondereye.core.RoadTiles.tilesFor(b[0], b[1], b[2], b[3]).take(MAX_ROAD_TILES)
            val results = withContext(Dispatchers.IO) {
                tiles.map { (x, y) -> async { Feeds.roadTile(cacheDir, x, y) } }.map { it.await() }
            }
            roadsLoading = false
            val roads = results.filterIsInstance<Net.Outcome.Ok<List<com.verisonder.sondereye.core.Road>>>().flatMap { it.value }
            val failed = results.filterIsInstance<Net.Outcome.Failed>()
            if (failed.size == results.size && failed.isNotEmpty()) {
                state.roadsProblem = failed.first().message
                return@run
            }
            val set = withContext(Dispatchers.Default) {
                val origin = Geo.ecef(c[0], c[1])
                RoadSet(origin, com.verisonder.sondereye.core.OsmRoads.ribbons(roads, origin, ROAD_LIFT_M), com.verisonder.sondereye.globe.GlobeRenderer.ROAD_PASSES)
            }
            if (!roadsWanted()) return@run
            // Some tiles missing: show the rest, say so, and try the area again later.
            roadArea.centre = if (failed.isEmpty()) doubleArrayOf(c[0], c[1], r) else null
            state.roadsProblem = if (failed.isEmpty()) null else "${failed.first().message} (${failed.size} of ${results.size} road tiles)"
            globe?.setRoads(set, ROADS_VECTOR_ALT)
        }
    }

    // ---- Conflicts (GDELT) ----------------------------------------------------------------

    private fun loadConflicts() {
        if (!state.layers.conflicts) return clear(state.conflicts, "conflicts", "x:")
        state.conflicts.loading = true
        state.conflicts.attemptAt = System.currentTimeMillis()
        run("conflicts") {
            val ev = com.verisonder.sondereye.core.GdeltEvents
            val latest = withContext(Dispatchers.IO) { Feeds.gdeltLatest() }
            if (latest !is Net.Outcome.Ok) {
                state.conflicts.loading = false
                state.conflicts.error = (latest as Net.Outcome.Failed).message
                return@run
            }
            // The day's files: those already on the phone, and the newest few that are not.
            val day = ev.lastFiles(latest.value, CONFLICT_DAY_FILES)
            val dir = java.io.File(cacheDir, "gdelt")
            val have = dir.list()?.toSet() ?: emptySet()
            val keep = day.map { ev.stampOf(it) + ".tsv" }.toSet()
            withContext(Dispatchers.IO) { have.filter { it !in keep }.forEach { java.io.File(dir, it).delete() } }
            val wanted = day.filter { ev.stampOf(it) + ".tsv" in have } + day.take(CONFLICT_NEW_FILES).filter { ev.stampOf(it) + ".tsv" !in have }
            val results = withContext(Dispatchers.IO) {
                wanted.distinct().map { u -> async { Feeds.gdeltSlice(cacheDir, u) } }.map { it.await() }
            }
            val ok = results.filterIsInstance<Net.Outcome.Ok<List<com.verisonder.sondereye.core.GdeltEvents.Event>>>()
            val failed = results.filterIsInstance<Net.Outcome.Failed>()
            state.conflictsHours = (ok.size * 15 + 59) / 60
            if (ok.isEmpty() && failed.isNotEmpty()) {
                state.conflicts.loading = false
                state.conflicts.error = failed.first().message
                return@run
            }
            val places = withContext(Dispatchers.Default) { ev.places(ok.flatMap { it.value }) }
            settle(state.conflicts, Net.Outcome.Ok(places to 0), "conflicts", ::conflictMarkers)
            // Some files missing: the rest show, and the next round tries again.
            if (failed.isNotEmpty()) state.conflicts.error = "${failed.first().message} (${failed.size} of ${results.size} files)"
        }
    }

    private fun conflictMarkers(list: List<Conflict>) = list.map { c ->
        // Bigger where more of the news is about it.
        val size = (8.0 + 3.0 * kotlin.math.ln(c.count.coerceAtLeast(1).toDouble()) / kotlin.math.ln(2.0)).coerceIn(8.0, 22.0)
        Marker(c.key, c.lat, c.lon, size.toFloat() * density, Palette.conflict.toArgb())
    }

    // ---- Bus lines (OpenStreetMap) -------------------------------------------------------


    private fun loadBusLines(force: Boolean = false) {
        if (!state.layers.busLines) {
            state.busLinesNote = null
            busCell = null
            state.busStops = emptyList()
            stopsShown = false
            globe?.setLayer("busStops", emptyList())
            globe?.setHighlight(null)
            clear(state.busLines, "busLines", "bl:")
            if (state.selected?.key?.startsWith("bs:") == true) select(null)
            drawBusLines()
            return
        }
        val c = globe?.center() ?: return
        state.busLines.attemptAt = System.currentTimeMillis()
        if (force) busCell = null
        if (c[2] > BUS_LINES_MAX_ALT) {
            state.busLinesNote = "zoom in below ${(BUS_LINES_MAX_ALT / 1000).toInt()} km to load them"
            return
        }
        state.busLinesNote = null
        // A fixed square of the map (so the same city gives the same square every time), kept
        // on the phone for a week: the second time, a city's lines show at once.
        val cell = busCellOf(c[0], c[1])
        val b = busCellBox(cell)
        val file = java.io.File(cacheDir, "buslines/$cell.json")
        state.busLines.loading = true
        run("busLines") {
            val out = withContext(Dispatchers.IO) {
                val saved = if (file.exists() && System.currentTimeMillis() - file.lastModified() < BUS_CACHE_MS) {
                    runCatching { com.verisonder.sondereye.core.BusLines.parse(file.readText()) }.getOrNull()
                } else null
                if (saved != null) Net.Outcome.Ok(saved) else Feeds.busLines(b[0], b[1], b[2], b[3], saveTo = file)
            }
            state.busLines.loading = false
            when (out) {
                is Net.Outcome.Ok -> {
                    busCell = cell
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

    /**
     * The box loaded still covers the view: zooming in within it needs nothing new (the
     * routes are already there), unlike the other layers, which follow the view's size.
     */
    private fun busLinesStale(): Boolean {
        val c = globe?.center() ?: return false
        if (c[2] > BUS_LINES_MAX_ALT) return busCell == null && state.busLinesNote == null
        return busCellOf(c[0], c[1]) != busCell
    }

    private var busCell: String? = null

    private fun busCellOf(lat: Double, lon: Double) =
        "${kotlin.math.floor(lat / BUS_CELL_DEG).toInt()}_${kotlin.math.floor(lon / BUS_CELL_DEG).toInt()}"

    /** The square with a margin round it, so a view near its edge still has its routes. */
    private fun busCellBox(cell: String): DoubleArray {
        val (y, x) = cell.split('_').map { it.toInt() }
        return doubleArrayOf(y * BUS_CELL_DEG - BUS_CELL_MARGIN, x * BUS_CELL_DEG - BUS_CELL_MARGIN,
            (y + 1) * BUS_CELL_DEG + BUS_CELL_MARGIN, (x + 1) * BUS_CELL_DEG + BUS_CELL_MARGIN)
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
            // A picked line is drawn on its own, bold (below); the rest step back behind it.
            val alpha = if (sel == null) 0.85f else 0.12f
            GlobeLine(pts, 0xFF000000.toInt() or l.shown, alpha, pairs = true)
        }
        globe?.setLines("busLines", if (sel == null) lines else lines.filterIndexed { i, _ -> state.busLines.items[i].id != sel })
        // The picked route: a dark edge and its colour, wide, over the map and the other lines.
        val picked = state.busLines.items.firstOrNull { it.id == sel }
        globe?.setHighlight(picked?.let { l ->
            val pts = l.paths.flatten()
            val mid = pts.getOrNull(pts.size / 2) ?: return@let null
            val origin = Geo.ecef(mid[0], mid[1])
            val ribbons = com.verisonder.sondereye.core.OsmRoads.ribbons(l.paths.map { com.verisonder.sondereye.core.Road(0, it) }, origin, 6.0)
            RoadSet(origin, ribbons, listOf(
                com.verisonder.sondereye.globe.RibbonPass(0, 9f, 0x0A0F0C, 0.9f),
                com.verisonder.sondereye.globe.RibbonPass(0, 5f, l.shown, 1f),
            ))
        })
        showBusStops(force = true)
    }

    private var stopsShown = false

    /** Stops only close in: a city's worth of dots from higher up is noise. */
    /**
     * Stops close in; with a line picked, only its stops, larger and in its colour, at any
     * height where its route shows.
     */
    private fun showBusStops(force: Boolean = false) {
        val alt = globe?.center()?.get(2) ?: return
        val picked = (state.selected as? Sel.OfBusLine)?.l
        val want = state.layers.busLines && (alt <= BUS_STOPS_MAX_ALT || picked != null)
        if (want == stopsShown && !force) return
        stopsShown = want
        globe?.setLayer("busStops", when {
            !want -> emptyList()
            picked != null -> state.busStops.filter { it.id in picked.stopIds }.map { s ->
                Marker("bs:" + s.id, s.lat, s.lon, 20f * density, 0xFF000000.toInt() or picked.shown, shape = Marker.SHAPE_BUS)
            }
            // A bus on a blue sign, like the stops in the street (a picked line's stops take its colour).
            else -> state.busStops.map { s -> Marker("bs:" + s.id, s.lat, s.lon, 16f * density, BUS_STOP_BLUE, shape = Marker.SHAPE_BUS) }
        })
    }

    /**
     * Where to fly, and how high, for things seen close in: buses and their lines stay at
     * street scale; a fire or webcam picked from the worldwide list goes low enough for its
     * area to load around it. Null: the usual fly-to.
     */
    private fun closePlace(sel: Sel): Pair<DoubleArray, Double>? {
        val alt = globe?.center()?.get(2) ?: return null
        return when (sel) {
            is Sel.OfFire -> doubleArrayOf(sel.h.lat, sel.h.lon) to FIRE_FLY_ALT
            is Sel.OfWebcam -> doubleArrayOf(sel.w.lat, sel.w.lon) to WEBCAM_FLY_ALT
            else -> busPlace(sel)?.let { it to alt.coerceIn(1_500.0, BUS_FLY_ALT) }
        }
    }

    /** The worldwide set behind the fires or webcams list, downloaded if missing or old. */
    private fun loadWorldFor(layer: String) {
        val now = System.currentTimeMillis()
        if (state.worldLoading == layer) return
        when (layer) {
            "fires" -> {
                val key = state.keys.firms
                if (key.isEmpty() || (state.worldFires != null && now - worldFiresAt < WORLD_FIRES_MS)) return
                state.worldLoading = layer; state.worldError = null
                run("worldFires") {
                    val out = withContext(Dispatchers.IO) { map(Feeds.firesWorld(key)) { Firms.strongest(it, WORLD_FIRES) } }
                    state.worldLoading = null
                    when (out) {
                        is Net.Outcome.Ok -> { state.worldFires = out.value; worldFiresAt = System.currentTimeMillis() }
                        is Net.Outcome.Failed -> state.worldError = out.message
                    }
                }
            }
            "webcams" -> {
                val key = state.keys.windy
                if (key.isEmpty() || (state.worldWebcams != null && now - worldWebcamsAt < WORLD_WEBCAMS_MS)) return
                state.worldLoading = layer; state.worldError = null
                run("worldWebcams") {
                    val out = withContext(Dispatchers.IO) { Feeds.topWebcams(key, WORLD_WEBCAM_PAGES) }
                    state.worldLoading = null
                    when (out) {
                        is Net.Outcome.Ok -> { state.worldWebcams = out.value; worldWebcamsAt = System.currentTimeMillis() }
                        is Net.Outcome.Failed -> state.worldError = out.message
                    }
                }
            }
        }
    }

    /** Where to fly for a bus thing, or null when it is not one. */
    /** Which globe layers and line groups each list's "Hide" keeps off the map. */
    private fun applyHidden() {
        val h = state.layers.hidden
        val markers = h.flatMap { name ->
            when (name) {
                "sats" -> listOf("sats")
                "busLines" -> listOf("busStops")
                else -> listOf(name)
            }
        }.toSet()
        val lines = buildSet {
            if ("flights" in h) add("trails")
            if ("busLines" in h) add("busLines")
            if ("sats" in h) add("orbit")
        }
        globe?.setHidden(markers, lines)
    }

    private fun busPlace(sel: Sel): DoubleArray? = when (sel) {
        is Sel.OfBusStop -> doubleArrayOf(sel.s.lat, sel.s.lon)
        is Sel.OfBus -> doubleArrayOf(sel.b.lat, sel.b.lon)
        is Sel.OfBusLine -> {
            val all = sel.l.paths.flatten()
            if (all.isEmpty()) null else all[all.size / 2]
        }
        else -> null
    }

    // ---- Explaining a conflict place -------------------------------------------------------

    /** Asks Gemini (the user's own key) what happened at [c], from its stories. */
    private fun explain(c: Conflict) {
        val key = state.keys.gemini
        if (key.isEmpty()) {
            state.explainProblem = c.key to "Add your Gemini key in the menu (API keys) to explain this."
            return
        }
        state.explaining = c.key
        state.explainProblem = null
        run("explain") {
            val out = withContext(Dispatchers.IO) { Feeds.explain(key, c.name, c.articles, state.brief.prefs.language) }
            state.explaining = null
            when (out) {
                is Net.Outcome.Ok -> state.explained = state.explained + (c.key to out.value)
                is Net.Outcome.Failed -> state.explainProblem = c.key to out.message
            }
        }
    }

    // ---- Riding a bus ---------------------------------------------------------------------

    /**
     * Rides [line] (null: stops): the map follows you along it, and its card says the next
     * stop, how far, and how many are left. Needs your position, so it turns that on.
     */
    private fun ride(line: BusLine?) {
        state.riding = line
        state.ride = null
        if (line == null) {
            return
        }
        select(Sel.OfBusLine(line))
        if (!state.layers.location) change(state.layers.copy(location = true))
        else if (hasLocationPermission()) where.refresh() else askPermission()
        state.me?.let { loc ->
            globe?.flyTo(loc.latitude, loc.longitude, (globe?.center()?.get(2) ?: RIDE_ALT).coerceIn(400.0, RIDE_ALT))
            updateRide(loc)
        }
    }

    private fun updateRide(loc: Location) {
        val line = state.riding ?: return
        val stops = line.stopIds.mapNotNull { id -> state.busStops.firstOrNull { it.id == id }?.let { doubleArrayOf(it.lat, it.lon) } }
        state.ride = com.verisonder.sondereye.core.Ride.progress(stops, loc.latitude, loc.longitude)
        state.rideSpeed = if (loc.hasSpeed()) loc.speed else null
        globe?.lookAt(loc.latitude, loc.longitude) // the map keeps you in the middle
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
            // Locals: the ship type lives in the shared module, where smart casts do not reach.
            val sog = sh.sogKt
            val cog = sh.cog
            val p = if (sog != null && sog > 0.5 && cog != null)
                Motion.ahead(sh.lat, sh.lon, cog, sog, ((now - sh.atMs) / 1000.0).coerceAtMost(600.0))
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
        if (force) { webcamArea.centre = null; state.worldWebcams = null }
        if (c[2] > WEBCAMS_MAX_ALT) {
            // From high up: the most popular webcams on Earth. Image links expire after 15 min.
            webcamArea.centre = null
            val cached = state.worldWebcams
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
                    state.worldWebcams = out.value; worldWebcamsAt = System.currentTimeMillis()
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
        if (force) { fireArea.centre = null; state.worldFires = null }
        if (c[2] > FIRES_MAX_ALT) {
            // From high up: the strongest fires on Earth, one download kept for 30 minutes.
            fireArea.centre = null
            val cached = state.worldFires
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
                    state.worldFires = out.value; worldFiresAt = System.currentTimeMillis()
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
            }
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
        // Near the view, or else in the worldwide set a list was showing.
        key.startsWith("w:") -> (state.webcams.items.firstOrNull { "w:" + it.id == key } ?: state.worldWebcams?.firstOrNull { "w:" + it.id == key })?.let { Sel.OfWebcam(it) }
        key.startsWith("h:") -> (state.fires.items.firstOrNull { Sel.OfFire(it).key == key } ?: state.worldFires?.firstOrNull { Sel.OfFire(it).key == key })?.let { Sel.OfFire(it) }
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

    /** A PC asks no permission for its (approximate) place. */
    private fun hasLocationPermission() = true

    private fun askPermission() = where.start()

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
            else -> where.refresh()
        }
    }

    // ---- Misc ---------------------------------------------------------------------------

    fun onResume() {
        globe?.onResume()
    }

    fun onPause() {
        globe?.onPause()
    }

    /** Keyboard (Windows): W A S D move the map, E picks the nearest point, Enter opens it. */
    fun keyMove(dx: Int, dy: Int, down: Boolean) = globe?.keyMove(dx, dy, down)
    fun keyNearest() = globe?.focusNearest()
    fun keyOpen() = globe?.openFocused()
    /** Held: Ctrl zooms in, Space zooms out ([rate] in e-folds of height a second; 0 stops). */
    fun keyZoom(rate: Double) = globe?.zoomHold(rate)

    /** The window is closing. */
    fun close() {
        ais.close()
        globe?.release()
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
        /** Closer than this, the app draws the roads itself (Esri's layer has no lines there). */
        private const val ROADS_VECTOR_ALT = 3_500.0
        private const val ROAD_LIFT_M = 2.0
        private const val MAX_ROAD_TILES = 36
        private const val BUS_LINES_MAX_ALT = 40_000.0
        /** Bus routes load by squares of the map this size (degrees), plus a margin, kept a week. */
        private const val BUS_CELL_DEG = 0.15
        private const val BUS_CELL_MARGIN = 0.03
        private const val BUS_CACHE_MS = 7 * 24 * 3_600_000L
        /** The globe starts its fly-in under the start-up screen and lands after it fades. */
        private const val BOOT_FLY_DELAY_MS = 1_100L
        private const val BOOT_FLY_MS = 2_300L
        private const val BUS_STOPS_MAX_ALT = 12_000.0
        private const val BUS_LINE_LIFT_M = 4.0 // just above the ground, never under it
        private const val BUS_FLY_ALT = 8_000.0
        private const val BUS_STOP_BLUE = 0xFF1E6FD9.toInt()
        /** Height the map follows a ride from: the street and the next stops in view. */
        private const val RIDE_ALT = 1_500.0
        /** Low enough for the area's own fires and webcams to load. */
        private const val FIRE_FLY_ALT = 400_000.0
        private const val WEBCAM_FLY_ALT = 40_000.0
        private const val BUSES_MAX_ALT = 300_000.0
        private const val BUSES_MS = 30_000L
        /** GDELT updates every 15 minutes. */
        private const val CONFLICTS_MS = 15 * 60_000L
        /** A day of GDELT's 15-minute event files; the first time, only the newest few (about a MB each). */
        private const val CONFLICT_DAY_FILES = 96
        private const val CONFLICT_NEW_FILES = 8
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
