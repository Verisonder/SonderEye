package com.verisonder.sondereye.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.verisonder.sondereye.core.Camera
import com.verisonder.sondereye.core.Hotspot
import com.verisonder.sondereye.core.Ship
import com.verisonder.sondereye.core.Webcam
import com.verisonder.sondereye.data.Keys
import com.verisonder.sondereye.core.EARTH_R
import com.verisonder.sondereye.core.Forecast
import com.verisonder.sondereye.core.Flight
import com.verisonder.sondereye.core.Fmt
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.NatEvent
import com.verisonder.sondereye.core.Pass
import com.verisonder.sondereye.core.Period
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.core.Weather
import com.verisonder.sondereye.data.Layers
import com.verisonder.sondereye.data.MapStyle
import com.verisonder.sondereye.data.SatGroup
import com.verisonder.sondereye.globe.GlobeView
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class Actions(
    val refresh: () -> Unit,
    val change: (Layers) -> Unit,
    val select: (Sel?) -> Unit,
    val home: () -> Unit,
    val myLocation: () -> Unit,
    val fixLocation: () -> Unit,
    val sky: () -> Unit,
    val search: (String) -> Unit,
    val pick: (Hit) -> Unit,
    /** Follow the aircraft with this hex, or stop (null). */
    val follow: (String?) -> Unit,
    val clearCache: () -> Unit,
    val measureCache: () -> Unit,
    val saveKeys: (Keys) -> Unit,
)

private class LastSel { var sel: Sel? = null }

private val PanelShape = RoundedCornerShape(16.dp)

@Composable
fun EyeScreen(state: EyeState, globeView: GlobeView?, actions: Actions) {
    // Relative times ("12 min ago") stay current without a refresh.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    BackHandler(enabled = state.layersOpen || state.selected != null || state.search.open) {
        when {
            state.search.open -> state.search.open = false
            state.layersOpen -> state.layersOpen = false
            else -> actions.select(null)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.space)) {
        if (globeView != null) {
            AndroidView(
                factory = { globeView.also { v -> (v.parent as? ViewGroup)?.removeView(v) } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Required by the map providers. Sits under the card when one is open.
        Text(
            state.credits.joinToString("  ·  "),
            color = Palette.dim.copy(alpha = 0.8f),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, bottom = 4.dp),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusCard(state, actions, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(Icons.Default.Menu, "Layers") {
                    state.layersOpen = !state.layersOpen
                    if (state.layersOpen) actions.measureCache()
                }
                RoundButton(Icons.Default.Search, "Search") { state.search.open = !state.search.open }
                val busy = state.quakes.loading || state.events.loading || state.sats.loading ||
                    (state.flights.loading && state.flights.updatedAt == null)
                if (busy) {
                    Box(
                        Modifier.size(44.dp).background(Palette.panel, CircleShape).border(1.dp, Palette.line, CircleShape),
                        Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
                    }
                } else {
                    RoundButton(Icons.Default.Refresh, "Refresh", onClick = actions.refresh)
                }
                RoundButton(Icons.Default.LocationOn, "Where I am", tint = if (state.me != null) Palette.me else Palette.text, onClick = actions.myLocation)
                RoundButton(Icons.Default.Star, "Sky view", onClick = actions.sky)
                RoundButton(Icons.Default.Home, "Whole Earth", onClick = actions.home)
            }
        }

        AnimatedVisibility(
            visible = state.selected != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            // Keeps showing the last selection while the card slides out.
            val last = remember { LastSel() }
            state.selected?.let { last.sel = it }
            last.sel?.let { SelectionCard(it, state, now, actions) }
        }

        if (state.search.open) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp, end = 66.dp, start = 12.dp)
            ) {
                SearchPanel(state.search, actions)
            }
        }

        if (state.layersOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.layersOpen = false } })
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp, end = 66.dp, start = 12.dp)
            ) {
                LayersPanel(state.layers, actions.change, state.cacheBytes, actions.clearCache, state.keys, actions.saveKeys)
            }
        }
    }
}

// ---- Status -------------------------------------------------------------------------------

@Composable
private fun StatusCard(state: EyeState, actions: Actions, modifier: Modifier) {
    val l = state.layers
    Column(
        modifier
            .background(Palette.panel, PanelShape)
            .border(1.dp, Palette.line, PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var any = false
        if (l.quakes) {
            any = true
            val q = state.quakes
            LayerLine(
                Palette.shallow,
                if (q.updatedAt == null && q.loading) "Loading earthquakes…" else count(q.items.size, "earthquake"),
                buildString {
                    append("${l.minMag.label}, ${l.period.label.lowercase()}")
                    q.updatedAt?.let { append(", ${clock(it)}") }
                    if (q.skipped > 0) append(", ${q.skipped} unreadable")
                },
            )
        }
        if (l.flights) {
            any = true
            val f = state.flights
            LayerLine(
                Palette.flight,
                if (f.updatedAt == null) "Loading flights…" else count(f.items.size, "flight"),
                "within 250 nm of the centre",
            )
        }
        if (l.satellites) {
            any = true
            val s = state.sats
            LayerLine(
                Palette.satellite,
                if (s.updatedAt == null && s.loading) "Loading satellites…" else count(s.items.size, "satellite"),
                buildString {
                    append(l.satGroup.label.lowercase())
                    if (state.satsDeep > 0) append(", ${state.satsDeep} high-orbit (approximate)")
                },
            )
        }
        if (l.events) {
            any = true
            val e = state.events
            LayerLine(
                Palette.event("wildfires"),
                if (e.updatedAt == null && e.loading) "Loading natural events…" else count(e.items.size, "natural event"),
                "open, last 30 days",
            )
        }
        if (l.radar) {
            any = true
            val at = state.radarFrameAt
            LayerLine(Color(0xFF3FA7FF), "Rain radar", if (at == null) "loading…" else "past hour, now showing ${clock(at)}")
        }
        if (l.cameras) {
            any = true
            val c = state.cameras
            LayerLine(
                Palette.alpr,
                state.camerasNote?.let { "Cameras" } ?: if (c.loading && c.updatedAt == null) "Loading cameras…" else count(c.items.size, "camera"),
                state.camerasNote ?: "${c.items.count { it.alpr }} plate readers, OpenStreetMap",
            )
        }
        if (l.ships) {
            any = true
            LayerLine(Palette.ship, if (state.shipsNote != null) "Ships" else count(state.ships.size, "ship"), state.shipsNote ?: "live AIS, AISStream")
        }
        if (l.webcams) {
            any = true
            LayerLine(Palette.webcam, if (state.webcamsNote != null) "Webcams" else count(state.webcams.items.size, "webcam"), state.webcamsNote ?: "near the centre, Windy")
        }
        if (l.fires) {
            any = true
            LayerLine(Palette.fire, if (state.firesNote != null) "Fires" else count(state.fires.items.size, "fire hotspot"), state.firesNote ?: "last 24 h, NASA FIRMS")
        }
        state.following?.let { hex ->
            val f = state.flights.items.firstOrNull { it.hex == hex }
            Text(
                "Following ${f?.callsign ?: hex.uppercase()}. Tap to stop.",
                color = Palette.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { actions.follow(null) },
            )
        }
        if (!any) Text("All layers off", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (l.location && state.me == null && state.meProblem == null) {
            Text("Finding where you are…", color = Palette.dim, fontSize = 13.sp)
        }

        val g = state.globeStatus
        if (g != null && g.failures == 0 && g.loading > 0) {
            Text("Loading imagery, ${g.loading} tiles…", color = Palette.dim, fontSize = 13.sp)
        }

        // Every failure, in red, with what it means.
        for (err in listOfNotNull(state.quakes.error, state.flights.error, state.sats.error, state.events.error, state.radar.error, state.cameras.error, state.webcams.error, state.fires.error)) {
            ErrorLine("$err. Tap to retry.", actions.refresh)
        }
        state.alertProblem?.let { ErrorLine(it, null) }
        state.shipsProblem?.let { ErrorLine(it, null) }
        state.meProblem?.let { ErrorLine(it, actions.fixLocation) }
        if (g != null && g.failures > 0) ErrorLine("Imagery: ${g.failures} tiles failed (${g.lastFailure}). Retrying every 20 s.", null)
        state.globeError?.let { ErrorLine(it, null) }
    }
}

@Composable
private fun LayerLine(dot: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.text, fontWeight = FontWeight.SemiBold)) { append(title) }
                withStyle(SpanStyle(color = Palette.dim)) { append("  $detail") }
            },
            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorLine(text: String, onClick: (() -> Unit)?) {
    Text(
        text, color = Palette.error, fontSize = 13.sp,
        modifier = Modifier.padding(top = 2.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, tint: Color = Palette.text, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(Palette.panel, CircleShape)
            .border(1.dp, Palette.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint) }
    }
}

// ---- Cards ---------------------------------------------------------------------------------

@Composable
private fun SelectionCard(sel: Sel, state: EyeState, now: Long, actions: Actions) {
    val context = LocalContext.current
    val close = { actions.select(null) }
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            when (sel) {
                is Sel.OfQuake -> QuakeBody(sel.q, now, context, close)
                is Sel.OfFlight -> FlightBody(sel.f, state, actions, context, close)
                is Sel.OfCamera -> CameraBody(sel.c, context, close)
                is Sel.OfShip -> ShipBody(state.ships[sel.s.mmsi] ?: sel.s, now, context, close)
                is Sel.OfWebcam -> WebcamBody(sel.w, context, close)
                is Sel.OfFire -> FireBody(sel.h, context, close)
                is Sel.OfSat -> SatBody(sel.s, state, actions, close)
                is Sel.OfEvent -> EventBody(sel.e, now, context, close)
                is Sel.OfPlace -> PlaceBody(sel, state, close)
                Sel.Me -> MeBody(state, now, close)
            }
        }
    }
}

@Composable
private fun Header(big: String?, bigColor: Color, title: String, sub: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        if (big != null) {
            Text(big, color = bigColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Palette.dim, fontSize = 13.sp)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Palette.dim) }
    }
}

@Composable
private fun Line(text: String, color: Color = Palette.dim) {
    Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
}

@Composable
private fun LinkRow(left: String, button: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (button != null) TextButton(onClick = onClick) { Text(button, color = Palette.accent) }
    }
}

@Composable
private fun ColumnScope.QuakeBody(q: Quake, now: Long, context: Context, onClose: () -> Unit) {
    Header(Fmt.mag(q.mag), Palette.depth(q.depthKm), q.place, "${Fmt.ago(q.timeMs, now)}, ${Fmt.depth(q.depthKm)}", onClose)
    Fmt.typeLabel(q.type)?.let { Line(it) }
    // USGS sets this flag for large oceanic events. It is not a warning itself.
    if (q.tsunami) Line("Large oceanic event: check tsunami.gov for warnings", Palette.error)
    LinkRow(fullTime(q.timeMs), if (q.url.isNotEmpty()) "Open on USGS" else null) { openUrl(context, q.url) }
}

@Composable
private fun ColumnScope.ShipBody(sh: Ship, now: Long, context: Context, onClose: () -> Unit) {
    Header(null, Palette.ship, sh.name ?: "MMSI ${sh.mmsi}", "MMSI ${sh.mmsi}", onClose)
    val parts = listOfNotNull(
        sh.sogKt?.let { "%.1f kn".format(it) },
        sh.cog?.let { "course ${it.roundToInt()}°" },
        sh.heading?.let { "heading ${it.roundToInt()}°" },
    )
    Line(parts.ifEmpty { listOf("No speed reported") }.joinToString(", "))
    LinkRow("AISStream, ${Fmt.ago(sh.atMs, now).lowercase()}", "Open on MarineTraffic") {
        openUrl(context, "https://www.marinetraffic.com/en/ais/details/ships/mmsi:${sh.mmsi}")
    }
}

@Composable
private fun ColumnScope.WebcamBody(w: Webcam, context: Context, onClose: () -> Unit) {
    Header(null, Palette.webcam, w.title, w.place ?: "", onClose)
    var img by remember(w.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(w.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(w.id) {
        val url = w.preview ?: run { failed = "No picture for this webcam"; return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                try {
                    if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                    android.graphics.BitmapFactory.decodeStream(c.inputStream)?.asImageBitmap() ?: error("not an image")
                } finally { c.disconnect() }
            }
        }
        r.onSuccess { img = it }.onFailure { failed = "Picture: ${it.message}" }
    }
    val bmp = img
    when {
        bmp != null -> Image(
            bmp, contentDescription = w.title, contentScale = ContentScale.Crop,
            modifier = Modifier.padding(end = 10.dp, top = 6.dp).fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp))
                .clickable { w.page?.let { openUrl(context, it) } },
        )
        failed != null -> Line(failed!!, Palette.error)
        else -> Line("Loading the picture…")
    }
    LinkRow("From Windy Webcams", if (w.page != null) "Open live" else null) { w.page?.let { openUrl(context, it) } }
}

@Composable
private fun ColumnScope.FireBody(h: Hotspot, context: Context, onClose: () -> Unit) {
    val conf = when (h.confidence) { "h" -> "high confidence"; "n" -> "nominal confidence"; "l" -> "low confidence"; else -> null }
    Header(null, Palette.fire, "Fire detected by satellite", listOfNotNull(conf, if (h.day) "daytime pass" else "night pass").joinToString(", "), onClose)
    Line(listOfNotNull(h.frpMw?.let { "intensity %.1f MW".format(it) }, "seen ${h.acquired} UTC").joinToString(", "))
    LinkRow("NASA FIRMS, VIIRS NOAA-20", "Open FIRMS map") {
        openUrl(context, "https://firms.modaps.eosdis.nasa.gov/map/#d:24hrs;@%.4f,%.4f,11.0z".format(java.util.Locale.ROOT, h.lon, h.lat))
    }
}

@Composable
private fun ColumnScope.CameraBody(c: Camera, context: Context, onClose: () -> Unit) {
    Header(
        null, if (c.alpr) Palette.alpr else Palette.camera,
        if (c.alpr) "Licence-plate reader" else "Surveillance camera",
        listOfNotNull(c.operator, c.direction?.let { "facing ${Sky.compass(it)}" }).joinToString(", "), onClose,
    )
    LinkRow("Mapped by OpenStreetMap volunteers", "Open in OSM") { openUrl(context, "https://www.openstreetmap.org/node/${c.id}") }
}

@Composable
private fun ColumnScope.FlightBody(f: Flight, state: EyeState, actions: Actions, context: Context, onClose: () -> Unit) {
    val sub = listOfNotNull(f.type, f.registration).joinToString(", ").ifEmpty { "ICAO ${f.hex.uppercase()}" }
    Header(null, Palette.text, f.callsign ?: f.hex.uppercase(), sub, onClose)
    val alt = when {
        f.onGround -> "On the ground"
        f.altFt != null -> "%,d ft".format(f.altFt)
        else -> "Altitude unknown"
    }
    val parts = listOfNotNull(alt, f.speedKt?.let { "${it.roundToInt()} kt" }, f.track?.let { "heading ${it.roundToInt()}°" })
    Line(parts.joinToString(", "))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val on = state.following == f.hex
        TextButton(onClick = { actions.follow(if (on) null else f.hex) }) {
            Text(if (on) "Stop following" else "Follow", color = Palette.accent)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { openUrl(context, "https://globe.adsb.lol/?icao=${f.hex}") }) { Text("Open on adsb.lol", color = Palette.accent) }
    }
}

@Composable
private fun ColumnScope.SatBody(s: Sgp4, state: EyeState, actions: Actions, onClose: () -> Unit) {
    val t = state.clock
    val p = s.ecefAt(t)
    val alt = p?.let { ((it.len() - EARTH_R) / 1000).roundToInt() }
    val speed = s.speedAt(t)
    Header(null, Palette.satellite, s.tle.name, "NORAD ${s.tle.norad}, one orbit every ${s.tle.periodMin.roundToInt()} min", onClose)
    Line(if (alt == null) "Decayed: the orbit data puts it below the surface" else "$alt km up, ${"%.2f".format(speed ?: 0.0)} km/s")
    if (s.deepSpace) Line("High orbit: position approximate, within about 50 km")
    val me = state.me
    if (me != null && p != null) {
        val a = Sky.lookAngles(me.latitude, me.longitude, p)
        Line(if (a[0] > 0) "Now ${a[0].roundToInt()}° above your horizon, towards ${Sky.compass(a[1])}" else "Now below your horizon")
    }

    Spacer(Modifier.size(6.dp))
    val passes = state.passes
    when {
        state.me == null -> Text(
            "Turn on your location to see when it passes over you.",
            color = Palette.accent, fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = actions.myLocation).padding(vertical = 4.dp),
        )
        passes == null -> Line("Working out passes over you…")
        passes.isEmpty() -> Line("No passes above 10° over you in the next 48 h.")
        else -> {
            Line("Next passes over you (above 10°):", Palette.text)
            for (pass in passes.take(4)) Line(passLine(pass) + if (pass.visible) ", visible" else "", if (pass.visible) Palette.satellite else Palette.dim)
            Line("Visible: the satellite is sunlit while your sky is dark.")
        }
    }
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun ColumnScope.EventBody(e: NatEvent, now: Long, context: Context, onClose: () -> Unit) {
    val sub = buildString {
        append(e.categoryTitle)
        e.timeMs?.let { append(", ${Fmt.ago(it, now)}") }
    }
    Header(null, Palette.event(e.category), e.title, sub, onClose)
    LinkRow("From NASA EONET", if (e.url != null) "Open source" else null) { e.url?.let { openUrl(context, it) } }
}

@Composable
private fun ColumnScope.MeBody(state: EyeState, now: Long, onClose: () -> Unit) {
    val me = state.me
    Header(null, Palette.me, "You are here", if (me == null) "" else "%.4f, %.4f".format(me.latitude, me.longitude), onClose)
    if (me != null) {
        val acc = if (me.hasAccuracy()) "within ${me.accuracy.roundToInt()} m" else "accuracy unknown"
        Line("$acc, ${Fmt.ago(me.time, now).lowercase()}")
    }
    WeatherLines(state)
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun ColumnScope.PlaceBody(p: Sel.OfPlace, state: EyeState, onClose: () -> Unit) {
    Header(null, Palette.accent, "Weather here", "%.4f, %.4f".format(p.lat, p.lon), onClose)
    WeatherLines(state)
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun WeatherLines(state: EyeState) {
    val w = state.weather ?: return
    val err = w.error
    val data = w.weather
    when {
        err != null -> Line(err, Palette.error)
        data == null -> Line("Getting the weather…")
        else -> {
            Text(
                "${data.tempC.roundToInt()} °C, ${data.description.lowercase()}",
                color = Palette.text, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Line(weatherDetail(data))
            w.forecast?.let { ForecastView(it) }
            Line("From Open-Meteo")
        }
    }
}

@Composable
private fun ForecastView(f: Forecast) {
    // Next 12 hours, every 2 hours, side by side; then the next days.
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (h in f.hours.take(13).filterIndexed { i, _ -> i % 2 == 0 }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h.timeLocal.substringAfter('T').take(5), color = Palette.dim, fontSize = 12.sp)
                Text("${h.tempC.roundToInt()}°", color = Palette.text, fontSize = 15.sp)
                Text(h.rainChance?.let { "$it%" } ?: "", color = Color(0xFF3FA7FF), fontSize = 11.sp)
            }
        }
    }
    for (d in f.days.drop(1).take(3)) {
        val day = runCatching {
            SimpleDateFormat("EEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(d.dateLocal)!!)
        }.getOrDefault(d.dateLocal)
        Line(
            "$day  ${d.maxC.roundToInt()}° / ${d.minC.roundToInt()}°, ${Weather(0.0, null, d.code, null, null, null, null).description.lowercase()}" +
                (d.rainChance?.let { ", rain $it%" } ?: ""),
        )
    }
}

private fun weatherDetail(w: Weather): String = listOfNotNull(
    w.windKmh?.let { k -> "wind ${k.roundToInt()} km/h" + (w.windFromDeg?.let { " from ${Sky.compass(it)}" } ?: "") },
    w.humidity?.let { "humidity $it%" },
    w.cloudPct?.let { "clouds $it%" },
    w.precipMm?.takeIf { it > 0 }?.let { "rain %.1f mm".format(it) },
).joinToString(", ").replaceFirstChar { it.uppercase() }

// ---- Search ----------------------------------------------------------------------------------

@Composable
private fun SearchPanel(se: SearchState, actions: Actions) {
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = se.query,
                onValueChange = { se.query = it },
                singleLine = true,
                placeholder = { Text("Place, flight (RAM101) or satellite", color = Palette.dim) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.search(se.query) }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                modifier = Modifier.fillMaxWidth(),
            )
            if (se.busy) Text("Searching…", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            for (e in se.errors) Text(e, color = Palette.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (!se.busy && se.searched && se.hits.isEmpty() && se.errors.isEmpty()) {
                Text("Nothing found.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (h in se.hits) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { actions.pick(h) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(h.title, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.detail, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---- Layers panel ----------------------------------------------------------------------------

@Composable
private fun LayersPanel(s: Layers, change: (Layers) -> Unit, cacheBytes: Long?, clearCache: () -> Unit, keys: Keys, saveKeys: (Keys) -> Unit) {
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.widthIn(max = 340.dp).border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Earthquakes", "USGS, updated every minute", s.quakes) { change(s.copy(quakes = it)) }
            Column(Modifier.alpha(if (s.quakes) 1f else 0.4f)) {
                Label("Minimum magnitude")
                ChipRow(MinMag.entries, s.minMag, { it.label }, s.quakes) { change(s.copy(minMag = it)) }
                Label("Period")
                ChipRow(Period.entries, s.period, { it.label }, s.quakes) { change(s.copy(period = it)) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Refresh every 5 min", color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = s.autoRefresh, enabled = s.quakes, onCheckedChange = { change(s.copy(autoRefresh = it)) })
                }
                Label("Colour is depth, size is magnitude")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Palette.shallow, "0–70 km")
                    Legend(Palette.mid, "70–300 km")
                    Legend(Palette.deep, "300+ km")
                }
            }

            Divider()
            Text("Map", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            ChipRow(MapStyle.entries, s.map, { it.label }, true) { change(s.copy(map = it)) }
            Column(Modifier.alpha(if (s.map != MapStyle.STREETS) 1f else 0.4f)) {
                Toggle("Roads", s.roads, s.map != MapStyle.STREETS) { change(s.copy(roads = it)) }
                Toggle("Place names and borders", s.labels, s.map != MapStyle.STREETS) { change(s.copy(labels = it)) }
            }
            Toggle("Day and night", s.dayNight, true) { change(s.copy(dayNight = it)) }
            Toggle("City lights at night", s.lights, s.dayNight) { change(s.copy(lights = it)) }

            Divider()
            Section("Rain radar", "RainViewer, last 10 minutes; long-press anywhere for its weather", s.radar) { change(s.copy(radar = it)) }

            Divider()
            Section("Flights", "adsb.lol, near the screen centre, every 10 s", s.flights) { change(s.copy(flights = it)) }
            Toggle("Trails (last 30 min)", s.trails, s.flights) { change(s.copy(trails = it)) }

            Divider()
            Section("Satellites", "CelesTrak orbits, positions computed live", s.satellites) { change(s.copy(satellites = it)) }
            Column(Modifier.alpha(if (s.satellites) 1f else 0.4f)) {
                Label("Group")
                ChipRow(SatGroup.entries, s.satGroup, { it.label }, s.satellites) { change(s.copy(satGroup = it)) }
            }
            Toggle("Alert me before visible ISS passes", s.passAlerts, true) { change(s.copy(passAlerts = it)) }

            Divider()
            Section("Natural events", "NASA EONET: fires, volcanoes, storms, ice", s.events) { change(s.copy(events = it)) }

            Divider()
            Section("Surveillance cameras", "OpenStreetMap; plate readers in red; load below 60 km", s.cameras) { change(s.copy(cameras = it)) }

            Divider()
            Section("Ships", "Live AIS from AISStream; loads below 2,000 km", s.ships) { change(s.copy(ships = it)) }
            KeyField("AISStream key", keys.ais, "aisstream.io → sign in with GitHub → API Keys") { saveKeys(keys.copy(ais = it)) }

            Divider()
            Section("Webcams", "Windy Webcams near the centre; loads below 1,000 km", s.webcams) { change(s.copy(webcams = it)) }
            KeyField("Windy key", keys.windy, "api.windy.com → Webcams API → free key") { saveKeys(keys.copy(windy = it)) }

            Divider()
            Section("Fire hotspots", "Every fire seen by NASA satellites, last 24 h", s.fires) { change(s.copy(fires = it)) }
            KeyField("FIRMS map key", keys.firms, "firms.modaps.eosdis.nasa.gov/api/map_key → your e-mail") { saveKeys(keys.copy(firms = it)) }

            Divider()
            Section("Where I am", "Your position, only while the app is open", s.location) { change(s.copy(location = it)) }

            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Map cache", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        cacheBytes?.let { "%.1f MB of map tiles on this phone".format(it / 1e6) } ?: "Measuring…",
                        color = Palette.dim, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = clearCache, enabled = (cacheBytes ?: 0) > 0) { Text("Clear", color = Palette.accent) }
            }
        }
    }
}

@Composable
private fun Section(title: String, sub: String, on: Boolean, toggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Palette.dim, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = toggle)
    }
}

/** A personal key: shown masked once saved, with how to get one. */
@Composable
private fun KeyField(label: String, saved: String, how: String, save: (String) -> Unit) {
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var text by remember { mutableStateOf("") }
    if (!editing) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label: ••••${saved.takeLast(4)}", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true; text = "" }) { Text("Change", color = Palette.accent) }
        }
        return
    }
    Text(how, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text(label, color = Palette.dim) },
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { save(text.trim()); editing = text.isBlank() }, enabled = text.isNotBlank()) { Text("Save", color = Palette.accent) }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, enabled: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, enabled = enabled, onCheckedChange = toggle)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.line)
}

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, enabled: Boolean, pick: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { pick(item) },
                enabled = enabled,
                label = { Text(label(item)) },
            )
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Palette.dim, fontSize = 12.sp)
    }
}

// ---- Formatting ------------------------------------------------------------------------------

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun fullTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun passLine(p: Pass): String {
    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(p.startMs))
    val minutes = ((p.endMs - p.startMs) / 60_000.0).roundToInt().coerceAtLeast(1)
    return "$day ${clock(p.startMs)}, $minutes min, up to ${p.maxElevationDeg.roundToInt()}°, " +
        "${Sky.compass(p.startAzDeg)} → ${Sky.compass(p.endAzDeg)}"
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
