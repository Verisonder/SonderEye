package com.verisonder.sondereye.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.verisonder.sondereye.core.Astro
import com.verisonder.sondereye.core.Flight
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.core.SkyProjection
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.data.Feeds
import com.verisonder.sondereye.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** Something to label in the sky. */
private class SkyThing(val label: String, val detail: String, val color: Color, val az: Double, val el: Double, val size: Float)

/**
 * Point the phone at the sky: the camera preview with satellites, aircraft, the Sun and
 * the Moon labelled where they are. Uses the rotation-vector sensor for direction and
 * the compass declination for true north. Portrait only (see the manifest).
 */
class SkyActivity : ComponentActivity(), SensorEventListener {

    private var rotation by mutableStateOf<FloatArray?>(null)
    private var things by mutableStateOf<List<SkyThing>>(emptyList())
    private var problems by mutableStateOf<List<String>>(emptyList())
    private var compassLow by mutableStateOf(false)
    private var cameraOk by mutableStateOf(false)
    private var me: Location? = null
    private var declination = 0.0
    private var sats: List<Sgp4> = emptyList()
    private var flights: List<Flight> = emptyList()
    private var flightsAt = 0L

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        if (!ok) addProblem("Camera: permission denied. The sky is drawn without the picture.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraOk = granted(Manifest.permission.CAMERA)
        if (!cameraOk) askCamera.launch(Manifest.permission.CAMERA)
        me = lastLocation()
        if (me == null) addProblem("Location: unknown. Turn on \"Where I am\" in the app first.")
        me?.let { declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble() }
        val focal = focalFactor()

        setContent {
            EyeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (cameraOk) CameraPreview()
                    SkyOverlay(focal)
                    Column(
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp)
                            .fillMaxWidth()
                            .background(Palette.panel, RoundedCornerShape(2.dp))
                            .border(1.dp, Palette.line, RoundedCornerShape(2.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("Sky view", color = Palette.text, fontSize = 16.sp)
                        Text(
                            "${things.count { it.el > 0 }} above the horizon: satellites, aircraft, Sun, Moon",
                            color = Palette.dim, fontSize = 13.sp,
                        )
                        if (compassLow) Text("Compass needs calibrating: move the phone in a figure 8.", color = Palette.error, fontSize = 13.sp)
                        for (p in problems) Text(p, color = Palette.error, fontSize = 13.sp)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val sm = getSystemService(SENSOR_SERVICE) as SensorManager
                val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (rv == null) addProblem("This phone has no rotation sensor, so the sky view cannot follow it.")
                else sm.registerListener(this@SkyActivity, rv, SensorManager.SENSOR_DELAY_GAME)
                try {
                    loadSatellites()
                    while (true) {
                        val loc = me
                        if (loc != null) {
                            if (System.currentTimeMillis() - flightsAt > 10_000) loadFlights(loc)
                            things = withContext(Dispatchers.Default) { compute(loc, System.currentTimeMillis()) }
                        }
                        delay(1000)
                    }
                } finally {
                    sm.unregisterListener(this@SkyActivity)
                }
            }
        }
    }

    private fun addProblem(p: String) {
        if (p !in problems) problems = problems + p
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * Focal length over sensor size of the back camera, portrait. The preview fills the
     * screen, so pixels per unit of tan(angle) = this × screen height (see [SkyOverlay]).
     */
    private fun focalFactor(): Pair<Double, Double>? = runCatching {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = cm.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first().toDouble()
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        // Portrait: the sensor's long side runs up the screen.
        f / size.width to f / size.height
    }.getOrNull()

    private suspend fun loadSatellites() {
        val all = ArrayList<Sgp4>()
        for (group in listOf("stations", "visual")) {
            val (result, problem) = withContext(Dispatchers.IO) { Feeds.satellites(cacheDir, group) }
            problem?.let(::addProblem)
            result?.tles?.forEach { t -> if (all.none { it.tle.norad == t.norad }) all.add(Sgp4(t)) }
        }
        sats = all
    }

    private suspend fun loadFlights(loc: Location) {
        flightsAt = System.currentTimeMillis()
        when (val out = withContext(Dispatchers.IO) { Feeds.flights(loc.latitude, loc.longitude) }) {
            is Net.Outcome.Ok -> flights = out.value.flights
            is Net.Outcome.Failed -> addProblem(out.message)
        }
    }

    private fun compute(loc: Location, now: Long): List<SkyThing> {
        val lat = loc.latitude
        val lon = loc.longitude
        val out = ArrayList<SkyThing>()
        fun add(label: String, detail: String, color: Color, p: V3, size: Float) {
            val a = Sky.lookAngles(lat, lon, p)
            if (a[0] > -10) out.add(SkyThing(label, detail, color, a[1], a[0], size))
        }
        add("Sun", "", Color(0xFFFFD34D), Astro.sun(now), 22f)
        add("Moon", "${(Astro.moonIllumination(now) * 100).roundToInt()}% lit", Color(0xFFE8ECF2), Astro.moon(now), 18f)
        val here = Geo.ecef(lat, lon)
        for (s in sats) {
            val p = s.ecefAt(now) ?: continue
            val km = ((p.len() - com.verisonder.sondereye.core.EARTH_R) / 1000).roundToInt()
            val lit = Astro.sunlit(p, now)
            add(s.tle.name, "$km km" + if (lit) "" else ", in shadow", if (lit) Palette.satellite else Palette.dim, p, if (s.tle.norad == MainActivity.ISS) 14f else 9f)
        }
        for (f in flights) {
            val altM = (f.altFt ?: 0) * 0.3048
            val p = Geo.ecef(f.lat, f.lon, altM)
            if ((p - here).len() > 150_000) continue // too far to see
            add(f.callsign ?: f.hex.uppercase(), if (f.onGround) "on the ground" else "%,d ft".format(f.altFt ?: 0), Palette.flight, p, 9f)
        }
        return out
    }

    @androidx.compose.runtime.Composable
    private fun CameraPreview() {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(this@SkyActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        }.onFailure { addProblem("Camera: could not start (${it.message})") }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    @androidx.compose.runtime.Composable
    private fun SkyOverlay(focal: Pair<Double, Double>?) {
        val r = rotation
        val list = things
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 38f; typeface = Typeface.DEFAULT_BOLD }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Palette.dim.toArgb() }
        Canvas(Modifier.fillMaxSize()) {
            if (r == null) return@Canvas
            val cx = size.width / 2.0
            val cy = size.height / 2.0
            // The preview fills the screen from a 4:3 sensor: whichever side it fills sets the scale.
            val f = if (focal != null) max(size.height * focal.first, size.width * focal.second)
            else size.height / (2 * tan(Math.toRadians(33.0)))
            fun at(az: Double, el: Double) = SkyProjection.project(r, declination, az, el, f, cx, cy)

            // Horizon and compass points.
            val horizon = Path()
            var started = false
            for (a in 0..360 step 3) {
                val p = at(a.toDouble(), 0.0)
                if (p == null) { started = false; continue }
                if (!started) horizon.moveTo(p[0].toFloat(), p[1].toFloat()) else horizon.lineTo(p[0].toFloat(), p[1].toFloat())
                started = true
            }
            drawPath(horizon, Color.White.copy(alpha = 0.45f), style = Stroke(width = 2.dp.toPx()))
            labelPaint.color = Color.White.toArgb()
            for ((name, az) in listOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)) {
                val p = at(az, 0.0) ?: continue
                drawContext.canvas.nativeCanvas.drawText(name, p[0].toFloat() - 12f, p[1].toFloat() + 44f, labelPaint)
            }

            for (t in list) {
                val p = at(t.az, t.el) ?: continue
                val o = Offset(p[0].toFloat(), p[1].toFloat())
                val below = t.el < 0
                drawCircle(t.color.copy(alpha = if (below) 0.35f else 1f), radius = t.size.dp.toPx() / 2, center = o)
                drawCircle(Color.Black.copy(alpha = 0.6f), radius = t.size.dp.toPx() / 2, center = o, style = Stroke(1.5f))
                labelPaint.color = t.color.copy(alpha = if (below) 0.5f else 1f).toArgb()
                val x = o.x + t.size.dp.toPx() / 2 + 8f
                drawContext.canvas.nativeCanvas.drawText(t.label, x, o.y + 6f, labelPaint)
                val detail = listOf(t.detail, if (below) "below the horizon" else "${t.el.roundToInt()}° up").filter { it.isNotEmpty() }.joinToString(", ")
                drawContext.canvas.nativeCanvas.drawText(detail, x, o.y + 40f, detailPaint)
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val m = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(m, e.values)
        rotation = m
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        compassLow = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }
}
