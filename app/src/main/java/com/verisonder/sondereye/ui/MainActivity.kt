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
import com.verisonder.sondereye.core.EARTH_R
import com.verisonder.sondereye.core.Flight
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.NatEvent
import com.verisonder.sondereye.core.Pass
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.data.Feeds
import com.verisonder.sondereye.data.Layers
import com.verisonder.sondereye.data.Net
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.data.Where
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
    /** Android stops showing the permission dialog after repeated denials. */
    private var locationBlocked = false

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
                state.me = loc
                state.meProblem = null
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
                override fun onStatus(status: GlobeStatus) { state.globeStatus = status }
                override fun onError(message: String) { state.globeError = message }
            })
            // Fixes the draw order: later layers on top.
            for (name in listOf("quakes", "events", "flights", "sats", "me")) globe?.setLayer(name, emptyList())
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
                    ),
                )
            }
        }

        refreshAll()

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
                        if (l.satellites && state.sats.items.isNotEmpty()) updateSatellites(now, orbit = tick % 30 == 0L)
                        if (l.flights && !state.flights.loading && now - state.flights.attemptAt >= FLIGHTS_MS) loadFlights()
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
        loadQuakes(); loadFlights(); loadSatellites(); loadEvents()
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
        val c = globe?.center() ?: return
        state.flights.loading = true
        state.flights.attemptAt = System.currentTimeMillis()
        run("flights") {
            val out = withContext(Dispatchers.IO) { Feeds.flights(c[0], c[1]) }
            settle(state.flights, map(out) { it.flights to it.skipped }, "flights") { list -> list.map(::flightMarker) }
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
        for (s in state.sats.items) {
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

    private fun flightMarker(f: Flight): Marker = Marker(
        "f:" + f.hex, f.lat, f.lon, 20f * density,
        (if (f.onGround) Palette.dim else Palette.flight).toArgb(),
        shape = Marker.SHAPE_PLANE, bearing = f.track ?: 0.0,
    )

    private fun eventMarker(e: NatEvent): Marker =
        Marker("e:" + e.id, e.lat, e.lon, 13f * density, Palette.event(e.category).toArgb())

    private fun meMarker(l: Location): Marker =
        Marker("me", l.latitude, l.longitude, 26f * density, Palette.me.toArgb(), shape = Marker.SHAPE_ME)

    // ---- Selection ----------------------------------------------------------------------

    private fun selectionFor(key: String): Sel? = when {
        key == "me" -> Sel.Me
        key.startsWith("q:") -> state.quakes.items.firstOrNull { "q:" + it.id == key }?.let { Sel.OfQuake(it) }
        key.startsWith("f:") -> state.flights.items.firstOrNull { "f:" + it.hex == key }?.let { Sel.OfFlight(it) }
        key.startsWith("s:") -> state.sats.items.firstOrNull { "s:" + it.tle.norad == key }?.let { Sel.OfSat(it) }
        key.startsWith("e:") -> state.events.items.firstOrNull { "e:" + it.id == key }?.let { Sel.OfEvent(it) }
        else -> null
    }

    private fun select(sel: Sel?) {
        state.selected = sel
        state.passes = null
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
            HttpResponseCache.install(File(cacheDir, "http"), 150L * 1024 * 1024)
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
        /** Camera height when flying to you: a city and its surroundings. */
        private const val ME_ALT = 25_000.0
        const val ISS = 25544
        const val CSS = 48274 // Tiangong
    }
}
