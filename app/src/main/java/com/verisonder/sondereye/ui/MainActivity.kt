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
import com.verisonder.sondereye.core.Camera
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
import com.verisonder.sondereye.data.Feeds
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
    /** Time of the radar frame on screen. */
    var radarFrameAt by mutableStateOf<Long?>(null)
    /** Bytes of map tiles on the phone (shown in the menu). */
    var cacheBytes by mutableStateOf<Long?>(null)
    /** Satellites added by a search, drawn even when their group is not shown. */
    var extraSats by mutableStateOf<List<Sgp4>>(emptyList())
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
                    globe?.flyTo(loc.latitude, loc.longitude, ME_ALT)
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
            for (name in listOf("quakes", "events", "cameras", "flights", "sats", "pin", "me")) globe?.setLayer(name, emptyList())
            applyMap()
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
                        myLocation = ::myLocation,
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
                    ),
                )
            }
        }

        refreshAll()
        if (state.layers.passAlerts) schedulePassAlerts()

        // Radar animation: the past hour's frames in a loop, holding on the latest.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var i = 0
                while (true) {
                    val frames = state.radar.items.drop(1).takeLast(RADAR_FRAMES)
                    if (state.layers.radar && frames.isNotEmpty()) {
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
                try {
                    var tick = 0L
                    while (true) {
                        val l = state.layers
                        val now = System.currentTimeMillis()
                        state.clock = now
                        if ((l.satellites && state.sats.items.isNotEmpty()) || state.extraSats.isNotEmpty()) updateSatellites(now, orbit = tick % 30 == 0L)
                        if (l.flights && !state.flights.loading && now - state.flights.attemptAt >= FLIGHTS_MS) loadFlights()
                        if (l.radar && !state.radar.loading && now - state.radar.attemptAt >= RADAR_MS) loadRadar()
                        if (l.flights && state.flights.items.isNotEmpty()) glideFlights(now)
                        if (l.cameras && !state.cameras.loading && now - state.cameras.attemptAt >= 15_000 && camerasStale()) loadCameras()
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
        if (new.radar != old.radar) loadRadar()
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
            overlays.add(TileSource.radar(r[0], frame))
            state.radarFrameAt = frame.substringAfterLast('/').toLongOrNull()?.times(1000)
        }
        globe?.setMap(base, overlays)
        globe?.setDayNight(l.dayNight)
        state.credits = (listOf(base) + overlays + TileSource.BLUE_MARBLE).map { it.credit }.distinct()
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

    private fun <T> clear(feed: Feed<T>, layer: String) {
        jobs[layer]?.cancel()
        feed.items = emptyList(); feed.loading = false; feed.error = null; feed.updatedAt = null
        globe?.setLayer(layer, emptyList())
        if (state.selected?.key?.startsWith("${layer.first()}:") == true) select(null)
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

    private fun flightMarker(f: Flight, fetchedAt: Long, now: Long): Marker {
        val p = glide(f, fetchedAt, now)
        return Marker(
            "f:" + f.hex, p[0], p[1], 20f * density,
            (if (f.onGround) Palette.dim else Palette.flight).toArgb(),
            shape = Marker.SHAPE_PLANE, bearing = f.track ?: 0.0,
        )
    }

    // ---- Flights: gliding, trails, follow ----------------------------------------------

    private var flightsAt = 0L
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
        key.startsWith("e:") -> state.events.items.firstOrNull { "e:" + it.id == key }?.let { Sel.OfEvent(it) }
        else -> null
    }

    private fun select(sel: Sel?) {
        state.selected = sel
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
    private fun myLocation() {
        if (!state.layers.location) {
            state.layers = state.layers.copy(location = true)
            store.save(state.layers)
        }
        val me = state.me
        if (me != null) {
            globe?.flyTo(me.latitude, me.longitude, ME_ALT)
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
        globe?.release()
        HttpResponseCache.getInstalled()?.flush()
        super.onDestroy()
    }

    companion object {
        private const val QUAKES_AUTO_MS = 5 * 60_000L
        /** adsb.lol is a free community service: one area request every 10 s is plenty. */
        private const val FLIGHTS_MS = 10_000L
        /** RainViewer publishes a frame every 10 minutes. */
        private const val RADAR_MS = 10 * 60_000L
        /** The last hour, looped. */
        private const val RADAR_FRAMES = 6
        /** Overpass boxes stay small: cameras load only below this height. */
        private const val CAMERAS_MAX_ALT = 60_000.0
        /** Camera height when flying to you: a city and its surroundings. */
        private const val ME_ALT = 25_000.0
        const val ISS = 25544
        const val CSS = 48274 // Tiangong
    }
}
