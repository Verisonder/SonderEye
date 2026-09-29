package com.verisonder.sondereye.globe

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.verisonder.sondereye.core.CameraState
import com.verisonder.sondereye.core.Fly
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.Pick
import com.verisonder.sondereye.core.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The globe. Owns the camera; the renderer only reads it. All gestures work by keeping
 * the ground under the finger under the finger, which is what makes it feel native.
 */
@SuppressLint("ViewConstructor")
class GlobeView(context: Context, private val listener: Listener) : GLSurfaceView(context) {

    interface Listener {
        /** A marker was tapped (its key), or empty globe (null). */
        fun onTap(key: String?)
        /** The user moved the map by hand (stops following a plane). */
        fun onUserGesture() {}
        /** Long press on the ground: (lat, lon). */
        fun onLongPress(lat: Double, lon: Double)
        fun onStatus(status: GlobeStatus)
        fun onError(message: String)
    }

    @Volatile private var cam = CameraState.HOME
    private val density = resources.displayMetrics.density
    private val renderer: GlobeRenderer
    private val layers = LinkedHashMap<String, List<Marker>>()
    private var all: List<Marker> = emptyList()
    private var markerLat = DoubleArray(0)
    private var markerLon = DoubleArray(0)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        renderer = GlobeRenderer(
            cacheDir = context.cacheDir,
            assets = context.assets,
            camera = { cam },
            density = density,
            requestRender = { requestRender() },
            onStatus = { s ->
                post { listener.onStatus(s) }
                // Failed tiles are retried on a later frame; make sure one comes while idle.
                if (s.failures > 0) postDelayed({ requestRender() }, 21_000)
            },
            onError = { m -> post { listener.onError(m) } },
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ---- API ----------------------------------------------------------------------

    /**
     * Replaces one layer's markers. Layers draw in the order they were first set, so the
     * later ones (you, satellites) sit on top.
     */
    fun setLayer(name: String, list: List<Marker>) {
        layers[name] = list
        all = layers.values.flatten()
        renderer.markers = all
        // Picking uses the altitude too: a satellite is picked where it is drawn.
        markerLat = DoubleArray(all.size) { all[it].lat }
        markerLon = DoubleArray(all.size) { all[it].lon }
        markerAlt = DoubleArray(all.size) { all[it].altM }
        requestRender()
    }

    private var markerAlt = DoubleArray(0)

    /** Rings the marker with [key] (or none); flies to it when [fly]. */
    fun select(key: String?, fly: Boolean) {
        renderer.selectedKey = key
        val m = if (key == null) null else all.firstOrNull { it.key == key }
        if (fly && m != null) flyTo(m.lat, m.lon, cam.alt.coerceIn(600_000.0, 4_000_000.0))
        requestRender()
    }

    fun flyTo(lat: Double, lon: Double, alt: Double) = flyTo(cam.copy(lat = lat, lon = lon, alt = alt))

    /** Centres on (lat, lon) at once, keeping height and heading (following something). */
    fun lookAt(lat: Double, lon: Double) {
        if (animation != null) return // let a fly-to finish first
        setCam(cam.copy(lat = lat, lon = lon))
    }

    private val lineGroups = LinkedHashMap<String, List<GlobeLine>>()

    /** Replaces one named group of lines (the orbit, the trails). */
    fun setLines(name: String, lines: List<GlobeLine>) {
        lineGroups[name] = lines
        renderer.lines = lineGroups.values.flatten()
        requestRender()
    }

    /** Street-level roads, drawn when closer than [maxAlt]; null clears them. */
    fun setRoads(set: RoadSet?, maxAlt: Double) {
        renderer.roads = set
        renderer.roadsMaxAlt = maxAlt
        requestRender()
    }

    /** One route drawn bold over the map (a picked bus line); null clears it. */
    fun setHighlight(set: RoadSet?) {
        renderer.highlight = set
        requestRender()
    }

    /** A satellite's orbit, or null to clear it. */
    fun setPath(points: List<com.verisonder.sondereye.core.V3>?) =
        setLines("orbit", if (points == null) emptyList() else listOf(GlobeLine(points, 0xFF4FC3F7.toInt())))

    fun setDayNight(on: Boolean) {
        renderer.dayNight = on
        requestRender()
    }

    /**
     * [lat, lon, alt, heading, metres per screen pixel] at the screen centre: the camera
     * target, its height and heading, and the ground scale for a scale bar.
     */
    fun center(): DoubleArray = cam.let {
        val v = view()
        doubleArrayOf(it.lat, it.lon, it.alt, it.heading, it.alt / v.focalPx)
    }

    /** Turns the map back to north up. */
    fun northUp() = flyTo(cam.copy(heading = 0.0), ms = 500)

    /** North stays up: twisting two fingers no longer turns the map. */
    var northLocked = false
        set(v) {
            field = v
            if (v) northUp()
        }

    fun home() = flyTo(CameraState.HOME)

    /**
     * The opening shot: from deep space, a third of the way round the planet, the globe
     * swings in to where the view is now. Touching the globe first cancels it.
     */
    fun intro(delayMs: Long, flyMs: Long) {
        val target = cam
        // Nothing on screen until the start-up screen lets it through ([reveal]): no flash of the map first.
        renderer.hold = true
        setCam(target.copy(lon = target.lon + 130.0, lat = target.lat - 15.0, alt = CameraState.MAX_ALT))
        introPending = true
        postDelayed({ if (introPending && animation == null) flyTo(target, flyMs); introPending = false }, delayMs)
    }

    private var introPending = false

    /** Lets the globe be drawn again after [intro]. */
    fun reveal() {
        if (!renderer.hold) return
        renderer.hold = false
        requestRender()
    }

    /**
     * Zooms toward the centre without stopping, [rate] in e-folds of height per second
     * (above 0 in, below 0 out), until called again with 0.
     */
    fun zoomHold(rate: Double) {
        zoomRate = rate
        if (rate == 0.0) {
            if (animation === zoomer) stopAnimation()
            return
        }
        if (animation !== zoomer) {
            stopAnimation()
            zoomAt = System.nanoTime()
            animation = zoomer
            postOnAnimation(zoomer)
        }
    }

    private var zoomRate = 0.0
    private var zoomAt = 0L
    private val zoomer = object : Runnable {
        override fun run() {
            if (animation !== this) return
            val now = System.nanoTime()
            val dt = ((now - zoomAt) / 1e9).coerceAtMost(0.1) // a stalled frame is not a jump
            zoomAt = now
            zoom(exp(zoomRate * dt), width / 2f, height / 2f)
            postOnAnimation(this)
        }
    }

    /** The map underneath and the transparent layers over it. */
    fun setMap(base: com.verisonder.sondereye.core.TileSource, overlays: List<com.verisonder.sondereye.core.TileSource>) {
        renderer.base = base
        renderer.overlays = overlays
        requestRender()
    }

    fun release() = renderer.shutdown()

    private fun setCam(c: CameraState) {
        cam = c.clamped()
        requestRender()
    }

    private fun view(): View = cam.view(maxOf(1, width), maxOf(1, height))

    // ---- Animation ----------------------------------------------------------------

    private var animation: Runnable? = null

    private fun stopAnimation() {
        animation?.let { removeCallbacks(it) }
        animation = null
    }

    private fun flyTo(target: CameraState, ms: Long = 1200) {
        stopAnimation()
        val from = cam
        val start = System.nanoTime()
        val r = object : Runnable {
            override fun run() {
                val t = (System.nanoTime() - start) / 1e6 / ms
                setCam(Fly.lerp(from, target, t))
                if (t < 1.0 && animation === this) postOnAnimation(this) else if (animation === this) animation = null
            }
        }
        animation = r
        postOnAnimation(r)
    }

    private fun fling(vx: Float, vy: Float) {
        stopAnimation()
        var velX = vx.toDouble()
        var velY = vy.toDouble()
        var last = System.nanoTime()
        val r = object : Runnable {
            override fun run() {
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                last = now
                panByPixels(velX * dt, velY * dt)
                val k = exp(-dt * 4.0)
                velX *= k; velY *= k
                if (hypot(velX, velY) > 30 && animation === this) postOnAnimation(this) else if (animation === this) animation = null
            }
        }
        animation = r
        postOnAnimation(r)
    }

    // ---- Movement -----------------------------------------------------------------

    /** Moves the camera so the ground point under (x0, y0) ends up under (x1, y1). */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        val v = view()
        val p0 = v.pick(x0.toDouble(), y0.toDouble())
        val p1 = v.pick(x1.toDouble(), y1.toDouble())
        if (p0 != null && p1 != null) {
            val a = Geo.latLon(p0)
            val b = Geo.latLon(p1)
            setCam(cam.copy(lat = cam.lat + (a[0] - b[0]), lon = cam.lon + Geo.wrapLon(a[1] - b[1])))
        } else {
            // Finger over space (around the whole-Earth view): rotate by screen distance.
            panByPixels((x1 - x0).toDouble(), (y1 - y0).toDouble())
        }
    }

    /** Degrees of arc per screen pixel at the centre. */
    private fun degreesPerPixel(v: View): Double {
        val cx = v.width / 2.0
        val cy = v.height / 2.0
        val a = v.pick(cx, cy)
        val b = v.pick(cx + 20, cy)
        return if (a != null && b != null) Geo.toDeg(Geo.angle(a, b)) / 20.0 else 90.0 / v.earthRadiusPx()
    }

    /** Content follows a finger moving (dx right, dy down) pixels. */
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
    private fun zoom(factor: Double, fx: Float, fy: Float) {
        val before = view().pick(fx.toDouble(), fy.toDouble())
        setCam(cam.copy(alt = cam.alt / factor))
        val after = view().pick(fx.toDouble(), fy.toDouble())
        if (before != null && after != null) {
            val a = Geo.latLon(before)
            val b = Geo.latLon(after)
            setCam(cam.copy(lat = cam.lat + (a[0] - b[0]), lon = cam.lon + Geo.wrapLon(a[1] - b[1])))
        }
    }

    // ---- Gestures -----------------------------------------------------------------

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom(d.scaleFactor.toDouble(), d.focusX, d.focusY)
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (held) return true // that was the end of a long hold, not a tap
            // Later layers are on top, so on a tie they win: search from the end.
            val i = Pick.nearest(view(), markerLat, markerLon, e.x.toDouble(), e.y.toDouble(), 30.0 * density, markerAlt)
            val key = all.getOrNull(i)?.key
            listener.onTap(key)
            select(key, fly = key != null)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = view().pick(e.x.toDouble(), e.y.toDouble()) ?: return true
            val ll = Geo.latLon(p)
            flyTo(
                cam.copy(
                    lat = cam.lat + (ll[0] - cam.lat) * 0.55,
                    lon = cam.lon + Geo.wrapLon(ll[1] - cam.lon) * 0.55,
                    alt = cam.alt * 0.45,
                ),
                ms = 350,
            )
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e2.pointerCount == 1) fling(vx, vy)
            return true
        }
    })

    init {
        // The detector's long-press (~0.4 s) fired by mistake while resting a finger on the
        // map: ours below needs a deliberate hold.
        taps.setIsLongpressEnabled(false)
    }

    /** A finger held still this long on the ground asks for the weather there. */
    private val holdMs = 1_000L
    private val slopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    /** The hold fired: the lift that ends it is not also a tap. */
    private var held = false
    private val hold = Runnable {
        val p = view().pick(downX.toDouble(), downY.toDouble()) ?: return@Runnable
        held = true
        val ll = Geo.latLon(p)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener.onLongPress(ll[0], ll[1])
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastAngle = Double.NaN
    private var tracking = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        introPending = false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) stopAnimation()
        // Long hold: armed on one finger down; any movement, second finger or lift cancels it.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                held = false
                removeCallbacks(hold)
                postDelayed(hold, holdMs)
            }
            MotionEvent.ACTION_MOVE -> if (hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slopPx) removeCallbacks(hold)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)

        // Focus (single finger, or the midpoint of two) drives panning; the angle between
        // two fingers drives rotation. Re-anchored whenever a finger lands or lifts.
        var fx = 0f
        var fy = 0f
        val n = e.pointerCount
        val lifting = e.actionMasked == MotionEvent.ACTION_POINTER_UP
        var used = 0
        for (i in 0 until n) {
            if (lifting && i == e.actionIndex) continue
            fx += e.getX(i); fy += e.getY(i); used++
        }
        if (used == 0) return true
        fx /= used; fy /= used
        val angle = if (used >= 2 && !lifting) atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble()) else Double.NaN

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = fx; lastY = fy; lastAngle = angle; tracking = true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (hypot((fx - lastX).toDouble(), (fy - lastY).toDouble()) > 3.0 || used >= 2) listener.onUserGesture()
                drag(lastX, lastY, fx, fy)
                if (!northLocked && !angle.isNaN() && !lastAngle.isNaN()) {
                    val d = Geo.toDeg(angle - lastAngle)
                    // Twisting the fingers clockwise turns the map with them.
                    setCam(cam.copy(heading = cam.heading - Geo.wrapLon(d)))
                }
                lastX = fx; lastY = fy; lastAngle = angle
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }
}
