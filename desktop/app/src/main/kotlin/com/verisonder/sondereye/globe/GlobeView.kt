package com.verisonder.sondereye.globe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.verisonder.sondereye.core.CameraState
import com.verisonder.sondereye.core.Fly
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.Pick
import com.verisonder.sondereye.core.TileSource
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.core.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The globe: the same calls as the phone's GlobeView, over Compose state, so the app's
 * logic is the phone's. [GlobeCanvas] draws it and feeds it the mouse.
 */
class GlobeView(cacheDir: File, val density: Float, private val listener: Listener) {
    interface Listener {
        fun onTap(key: String?)
        fun onUserGesture()
        fun onLongPress(lat: Double, lon: Double)
        fun onStatus(status: GlobeStatus)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var cam by mutableStateOf(CameraState.HOME)
        private set
    internal var markers by mutableStateOf<List<Marker>>(emptyList())
    internal var lines by mutableStateOf<List<GlobeLine>>(emptyList())
    internal var roads by mutableStateOf<RoadSet?>(null)
    internal var roadsMaxAlt by mutableStateOf(0.0)
    internal var highlight by mutableStateOf<RoadSet?>(null)
    internal var selectedKey by mutableStateOf<String?>(null)
    internal var dayNight by mutableStateOf(false)
    internal var base by mutableStateOf(TileSource.SATELLITE)
    internal var overlays by mutableStateOf<List<TileSource>>(emptyList())
    /** Draw nothing but space (the start-up screen is still up). */
    internal var hold by mutableStateOf(false)
    /** Bumped when a tile arrives: the canvas reads it, so it draws again. */
    internal var tilesVersion by mutableIntStateOf(0)

    internal val tiles = TileStore(cacheDir) { tilesVersion++ }

    /** Set by the canvas as it is laid out. */
    internal var width = 1
    internal var height = 1

    private val layers = LinkedHashMap<String, List<Marker>>()
    private val lineGroups = LinkedHashMap<String, List<GlobeLine>>()
    private var hiddenLayers: Set<String> = emptySet()
    private var hiddenLines: Set<String> = emptySet()
    private var highlightSet: RoadSet? = null
    private var all: List<Marker> = emptyList()
    private var markerLat = DoubleArray(0)
    private var markerLon = DoubleArray(0)
    private var markerAlt = DoubleArray(0)

    // ---- API (as on the phone) ------------------------------------------------------

    fun setLayer(name: String, list: List<Marker>) {
        layers[name] = list
        rebuildMarkers()
    }

    fun setHidden(markerLayers: Set<String>, lineGroups: Set<String>) {
        hiddenLayers = markerLayers
        hiddenLines = lineGroups
        rebuildMarkers()
        lines = this.lineGroups.filterKeys { it !in hiddenLines }.values.flatten()
        highlight = if ("busLines" in hiddenLines) null else highlightSet
    }

    private fun rebuildMarkers() {
        all = layers.filterKeys { it !in hiddenLayers }.values.flatten()
        markers = all
        markerLat = DoubleArray(all.size) { all[it].lat }
        markerLon = DoubleArray(all.size) { all[it].lon }
        markerAlt = DoubleArray(all.size) { all[it].altM }
    }

    /** Rings the marker with [key] (or none); flies to it when [fly]; [stayClose]: never further out. */
    fun select(key: String?, fly: Boolean, stayClose: Boolean = false) {
        selectedKey = key
        val m = if (key == null) null else all.firstOrNull { it.key == key }
        val alt = if (stayClose) cam.alt.coerceAtMost(4_000_000.0) else cam.alt.coerceIn(600_000.0, 4_000_000.0)
        if (fly && m != null) flyTo(m.lat, m.lon, alt)
    }

    fun flyTo(lat: Double, lon: Double, alt: Double) = flyTo(cam.copy(lat = lat, lon = lon, alt = alt))

    fun lookAt(lat: Double, lon: Double) {
        if (animation != null) return
        setCam(cam.copy(lat = lat, lon = lon))
    }

    fun setLines(name: String, lines: List<GlobeLine>) {
        lineGroups[name] = lines
        this.lines = lineGroups.filterKeys { it !in hiddenLines }.values.flatten()
    }

    fun setRoads(set: RoadSet?, maxAlt: Double) {
        roads = set
        roadsMaxAlt = maxAlt
    }

    fun setHighlight(set: RoadSet?) {
        highlightSet = set
        highlight = if ("busLines" in hiddenLines) null else set
    }

    fun setPath(points: List<V3>?) =
        setLines("orbit", if (points == null) emptyList() else listOf(GlobeLine(points, 0xFF4FC3F7.toInt())))

    fun setDayNight(on: Boolean) {
        dayNight = on
    }

    fun setMap(base: TileSource, overlays: List<TileSource>) {
        this.base = base
        this.overlays = overlays
    }

    /** [lat, lon, alt, heading, metres per screen pixel] at the screen centre. */
    fun center(): DoubleArray = cam.let {
        val v = view()
        doubleArrayOf(it.lat, it.lon, it.alt, it.heading, it.alt / v.focalPx)
    }

    fun northUp() = flyTo(cam.copy(heading = 0.0), ms = 500)

    var northLocked = false
        set(v) {
            field = v
            if (v) northUp()
        }

    fun home() = flyTo(CameraState.HOME)

    private var introPending = false

    /** The opening shot: from deep space, a third of the way round, swinging in to here. */
    fun intro(delayMs: Long, flyMs: Long) {
        val target = cam
        hold = true
        setCam(target.copy(lon = target.lon + 130.0, lat = target.lat - 15.0, alt = CameraState.MAX_ALT))
        introPending = true
        scope.launch {
            delay(delayMs)
            if (introPending && animation == null) flyTo(target, flyMs)
            introPending = false
        }
    }

    fun reveal() {
        hold = false
    }

    private var zoomRate = 0.0

    /** Zooms toward the centre without stopping, [rate] e-folds of height a second; 0 stops. */
    fun zoomHold(rate: Double) {
        zoomRate = rate
        if (rate == 0.0) {
            if (zooming) stopAnimation()
            return
        }
        if (!zooming) {
            stopAnimation()
            zooming = true
            animation = scope.launch {
                var last = System.nanoTime()
                while (isActive) {
                    delay(16)
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1e9).coerceAtMost(0.1)
                    last = now
                    zoom(exp(zoomRate * dt), width / 2f, height / 2f)
                }
            }
        }
    }

    private var zooming = false

    fun onPause() {}
    fun onResume() {}
    fun release() = tiles.shutdown()

    // ---- Camera -----------------------------------------------------------------------

    internal fun setCam(c: CameraState) {
        cam = c.clamped()
    }

    fun view(): View = cam.view(maxOf(1, width), maxOf(1, height))

    private var animation: Job? = null

    internal fun stopAnimation() {
        animation?.cancel()
        animation = null
        zooming = false
    }

    private fun flyTo(target: CameraState, ms: Long = 1200) {
        stopAnimation()
        val from = cam
        val start = System.nanoTime()
        animation = scope.launch {
            while (isActive) {
                val t = (System.nanoTime() - start) / 1e6 / ms
                setCam(Fly.lerp(from, target, t))
                if (t >= 1.0) break
                delay(12)
            }
            animation = null
        }
    }

    /** Moves the camera so the ground point under (x0, y0) ends up under (x1, y1). */
    internal fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        val v = view()
        val p0 = v.pick(x0.toDouble(), y0.toDouble())
        val p1 = v.pick(x1.toDouble(), y1.toDouble())
        if (p0 != null && p1 != null) {
            val a = Geo.latLon(p0)
            val b = Geo.latLon(p1)
            setCam(cam.copy(lat = cam.lat + (a[0] - b[0]), lon = cam.lon + Geo.wrapLon(a[1] - b[1])))
        } else {
            panByPixels((x1 - x0).toDouble(), (y1 - y0).toDouble())
        }
    }

    private fun degreesPerPixel(v: View): Double {
        val cx = v.width / 2.0
        val cy = v.height / 2.0
        val a = v.pick(cx, cy)
        val b = v.pick(cx + 20, cy)
        return if (a != null && b != null) Geo.toDeg(Geo.angle(a, b)) / 20.0 else 90.0 / v.earthRadiusPx()
    }

    private fun panByPixels(dx: Double, dy: Double) {
        val v = view()
        val dpp = degreesPerPixel(v)
        val h = Geo.toRad(cam.heading)
        val east = (-dx * cos(h) + dy * sin(h)) * dpp
        val north = (dx * sin(h) + dy * cos(h)) * dpp
        val c = cos(Geo.toRad(cam.lat)).coerceAtLeast(0.05)
        setCam(cam.copy(lat = cam.lat + north, lon = cam.lon + east / c))
    }

    /** Zooms by [factor] keeping the ground under the focus point fixed. */
    internal fun zoom(factor: Double, fx: Float, fy: Float) {
        val before = view().pick(fx.toDouble(), fy.toDouble())
        setCam(cam.copy(alt = cam.alt / factor))
        val after = view().pick(fx.toDouble(), fy.toDouble())
        if (before != null && after != null) {
            val a = Geo.latLon(before)
            val b = Geo.latLon(after)
            setCam(cam.copy(lat = cam.lat + (a[0] - b[0]), lon = cam.lon + Geo.wrapLon(a[1] - b[1])))
        }
    }

    /** Turns the map by [degrees] (unless north is locked). */
    internal fun turn(degrees: Double) {
        if (northLocked) return
        setCam(cam.copy(heading = cam.heading + degrees))
    }

    // ---- Mouse (from GlobeCanvas) ----------------------------------------------------------

    internal fun pressed() {
        introPending = false
        stopAnimation()
    }

    internal fun moved() = listener.onUserGesture()

    internal fun click(x: Float, y: Float) {
        val i = Pick.nearest(view(), markerLat, markerLon, x.toDouble(), y.toDouble(), 16.0 * density, markerAlt)
        val key = all.getOrNull(i)?.key
        listener.onTap(key)
        select(key, fly = key != null, stayClose = true)
    }

    internal fun doubleClick(x: Float, y: Float) {
        val p = view().pick(x.toDouble(), y.toDouble()) ?: return
        val ll = Geo.latLon(p)
        flyTo(
            cam.copy(
                lat = cam.lat + (ll[0] - cam.lat) * 0.55,
                lon = cam.lon + Geo.wrapLon(ll[1] - cam.lon) * 0.55,
                alt = cam.alt * 0.45,
            ),
            ms = 350,
        )
    }

    /** A held press, or a right-click, on the ground: what is here (weather, set my place). */
    internal fun hold(x: Float, y: Float) {
        val p = view().pick(x.toDouble(), y.toDouble()) ?: return
        val ll = Geo.latLon(p)
        listener.onLongPress(ll[0], ll[1])
    }

    internal fun status(s: GlobeStatus) = listener.onStatus(s)
}
