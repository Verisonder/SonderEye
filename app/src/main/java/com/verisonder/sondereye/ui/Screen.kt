package com.verisonder.sondereye.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.verisonder.sondereye.core.BriefPrefs
import com.verisonder.sondereye.core.News
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
    val northUp: () -> Unit,
    /** Open (true) or close the day's brief. */
    val brief: (Boolean) -> Unit,
    val reloadBrief: () -> Unit,
    /** Ask Gemini again, with the same stories. */
    val regenerateSummary: () -> Unit,
    val briefPrefs: (BriefPrefs) -> Unit,
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

    BackHandler(enabled = state.layersOpen || state.selected != null || state.search.open || state.brief.open || state.chromeHidden) {
        when {
            state.chromeHidden && state.selected == null -> state.chromeHidden = false
            state.brief.open -> actions.brief(false)
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

        val hidden = state.chromeHidden
        if (!hidden) CompassRose(state.view?.getOrNull(3) ?: 0.0, Modifier.align(Alignment.Center))

        // An open panel takes the space; the legend and readout step aside rather than show under it.
        // In the clean view everything steps aside.
        val panelOpen = state.layersOpen || state.search.open || state.brief.open || hidden

        if (hidden) {
            // The only control left: a small tab on the right edge that brings everything back.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp)
                    .size(width = 30.dp, height = 56.dp)
                    .background(Palette.panel.copy(alpha = 0.75f), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .clickable { state.chromeHidden = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Show the controls", tint = Palette.text)
            }
        }

        // Bottom left, as on a chart: scale bar, position, and the credits the providers require.
        if (!panelOpen) Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 14.dp, end = 80.dp, bottom = 6.dp),
        ) {
            state.view?.let { v ->
                ScaleBar(v)
                Text(position(v), color = Color.White, style = Figures.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            if (state.credits.isNotEmpty()) {
                Text(state.credits.joinToString(", "), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 2)
            }
        }

        if (!panelOpen) Legend(
            state, actions,
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, top = 12.dp, end = 76.dp)
                .widthIn(max = 380.dp),
        )

        // Right: one tool strip. Sideways the screen is shorter than the strip, so it scrolls.
        val stripMax = (LocalConfiguration.current.screenHeightDp - 40).coerceAtLeast(120)
        if (!hidden) Column(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(12.dp)
                .heightIn(max = stripMax.dp)
                .background(Palette.panel, RoundedCornerShape(14.dp))
                .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            Tool(Icons.Default.KeyboardArrowRight, "Hide the controls", false) {
                state.layersOpen = false
                state.search.open = false
                state.brief.open = false
                state.chromeHidden = true
            }
            ToolDivider()
            // North: tap to turn north up; long-press to lock it there (and again to unlock).
            val heading = state.view?.getOrNull(3) ?: 0.0
            val locked = state.layers.northLock
            val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            Box(
                Modifier
                    .size(48.dp)
                    .background(if (locked) Palette.magenta else Color.Transparent)
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = { actions.northUp() },
                            onLongPress = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                actions.change(state.layers.copy(northLock = !locked))
                            },
                        )
                    },
                Alignment.Center,
            ) {
                Text(
                    "N",
                    color = if (locked) Color.White else Palette.magenta,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.rotate(if (locked) 0f else -heading.toFloat()),
                )
            }
            ToolDivider()
            Tool(Icons.Default.Menu, "Layers and settings", state.layersOpen) {
                state.layersOpen = !state.layersOpen
                state.search.open = false
                state.brief.open = false
                if (state.layersOpen) actions.measureCache()
            }
            ToolDivider()
            Tool(Icons.Default.Search, "Search", state.search.open) {
                state.search.open = !state.search.open
                state.layersOpen = false
                state.brief.open = false
            }
            ToolDivider()
            Tool(Icons.Default.DateRange, "Today: weather and news", state.brief.open) { actions.brief(!state.brief.open) }
            ToolDivider()
            val busy = state.quakes.loading || state.events.loading || state.sats.loading ||
                (state.flights.loading && state.flights.updatedAt == null)
            if (busy) {
                Box(Modifier.size(48.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.magenta, strokeWidth = 2.dp)
                }
            } else {
                Tool(Icons.Default.Refresh, "Refresh", false, onClick = actions.refresh)
            }
            ToolDivider()
            Tool(Icons.Default.LocationOn, "Where I am", false, tint = if (state.me != null) Palette.me else Palette.text, onClick = actions.myLocation)
            ToolDivider()
            Tool(Icons.Default.Star, "Sky view", false, onClick = actions.sky)
            ToolDivider()
            Tool(Icons.Default.Home, "Whole Earth", false, onClick = actions.home)
        }

        AnimatedVisibility(
            visible = state.selected != null,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp)
                .widthIn(max = 460.dp), // a card, not a wall, when the phone is sideways
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            // Keeps showing the last selection while the card slides out.
            val last = remember { LastSel() }
            state.selected?.let { last.sel = it }
            last.sel?.let { SelectionCard(it, state, now, actions) }
        }

        if (state.brief.open) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp, end = 76.dp, start = 12.dp)
                    .widthIn(max = 460.dp)
            ) {
                BriefPanel(state, actions)
            }
        }

        if (state.search.open) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp, end = 72.dp, start = 12.dp)
                .widthIn(max = 420.dp)
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
                    .padding(top = 12.dp, end = 72.dp, start = 12.dp)
            ) {
                LayersPanel(state.layers, actions.change, state.cacheBytes, actions.clearCache, state.keys, actions.saveKeys, state.allCredits)
            }
        }
    }
}

// ---- Status -------------------------------------------------------------------------------

@Composable
private fun StatusCard(state: EyeState, actions: Actions, modifier: Modifier) {
    val l = state.layers
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * A chart legend: the real symbols from the globe with their counts. Tap it for every
 * layer's detail and every problem.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(state: EyeState, actions: Actions, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val problems = listOfNotNull(
        state.quakes.error, state.flights.error, state.sats.error, state.events.error, state.radar.error,
        state.cameras.error, state.webcams.error, state.fires.error, state.meProblem, state.alertProblem,
        state.shipsProblem, state.globeError,
    ).size + if ((state.globeStatus?.failures ?: 0) > 0) 1 else 0
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .background(Palette.panel, shape)
            .border(1.dp, if (problems > 0) Palette.error else Palette.line, shape)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
    ) {
        val l = state.layers
        fun n(f: Feed<*>) = if (f.updatedAt == null && f.loading) "…" else f.items.size.toString()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (l.quakes) Key(Sym.DOT, Palette.shallow, n(state.quakes), "quakes")
            if (l.flights) Key(Sym.PLANE, Palette.text, n(state.flights), "flights")
            if (l.satellites || state.extraSats.isNotEmpty()) Key(Sym.DIAMOND, Palette.satellite, (state.sats.items.size + state.extraSats.size).toString(), "satellites")
            if (l.ships) Key(Sym.PLANE, Palette.ship, if (state.shipsNote != null) "–" else state.ships.size.toString(), "ships")
            if (l.events) Key(Sym.DOT, Palette.event("wildfires"), n(state.events), "events")
            if (l.fires) Key(Sym.DOT, Palette.fire, if (state.firesNote != null) "–" else n(state.fires), "fires")
            if (l.cameras) Key(Sym.DOT, Palette.alpr, if (state.camerasNote != null) "–" else n(state.cameras), "cameras")
            if (l.webcams) Key(Sym.DIAMOND, Palette.webcam, if (state.webcamsNote != null) "–" else n(state.webcams), "webcams")
            if (l.radar) Key(Sym.RAIN, Color(0xFF3FA7FF), state.radarFrameAt?.let { clock(it) } ?: "…", "radar")
        }
        val g = state.globeStatus
        if (g != null && g.loading > 0 && g.failures == 0) {
            Text("Loading ${g.loading} map tiles", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (problems > 0 && !open) {
            Text(
                if (problems == 1) "1 problem. Tap for details." else "$problems problems. Tap for details.",
                color = Palette.error, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.following?.let { hex ->
            val f = state.flights.items.firstOrNull { it.hex == hex }
            Text(
                "Following ${f?.callsign ?: hex.uppercase()}. Tap here to stop.",
                color = Palette.magenta, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp).clickable { actions.follow(null) },
            )
        }
        if (open) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Palette.line)
            StatusCard(state, actions, Modifier)
        }
    }
}

/** One legend entry: symbol, count, name. */
@Composable
private fun Key(sym: Sym, color: Color, value: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Symbol(sym, color, 14.dp)
        Spacer(Modifier.width(5.dp))
        Text(value, color = Palette.text, style = Figures.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(3.dp))
        Text(name, color = Palette.dim, fontSize = 14.sp)
    }
}

/** The shapes the globe draws, so the legend and cards match the map. */
enum class Sym { DOT, PLANE, DIAMOND, RAIN, YOU }

@Composable
fun Symbol(sym: Sym, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) { drawSymbol(sym, color) }
}

private fun DrawScope.drawSymbol(sym: Sym, color: Color) {
    val w = size.width
    val c = Offset(w / 2, size.height / 2)
    val ink = Color(0xFF15171B)
    when (sym) {
        Sym.DOT -> {
            drawCircle(ink, w * 0.42f, c)
            drawCircle(color, w * 0.34f, c)
        }
        Sym.PLANE -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, w * 0.05f); lineTo(w * 0.86f, w * 0.92f); lineTo(w * 0.5f, w * 0.68f); lineTo(w * 0.14f, w * 0.92f); close()
            }
            drawPath(p, color)
            drawPath(p, ink, style = Stroke(1.2f))
        }
        Sym.DIAMOND -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, 0f); lineTo(w, w * 0.5f); lineTo(w * 0.5f, w); lineTo(0f, w * 0.5f); close()
            }
            drawPath(p, ink)
            val q = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, w * 0.16f); lineTo(w * 0.84f, w * 0.5f); lineTo(w * 0.5f, w * 0.84f); lineTo(w * 0.16f, w * 0.5f); close()
            }
            drawPath(q, color)
        }
        Sym.RAIN -> for (i in 0..2) {
            val x = w * (0.25f + i * 0.25f)
            drawLine(color, Offset(x, w * 0.2f), Offset(x - w * 0.12f, w * 0.8f), w * 0.12f)
        }
        Sym.YOU -> {
            drawCircle(color.copy(alpha = 0.25f), w * 0.5f, c)
            drawCircle(Color.White, w * 0.3f, c)
            drawCircle(color, w * 0.21f, c)
        }
    }
}

/**
 * VOR-style compass rose around the screen centre: ticks every 10°, numbers every 30°,
 * magenta north. It turns with the map, so it always tells where north is.
 */
@Composable
private fun CompassRose(heading: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val numStyle = TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f))
    val northStyle = numStyle.copy(color = Palette.magenta, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier.size(168.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.width / 2 - 14.dp.toPx()
        val shadow = Color.Black.copy(alpha = 0.35f)
        val tick = Color.White.copy(alpha = 0.6f)
        rotate(-heading.toFloat(), c) {
            for (deg in 0 until 360 step 10) {
                val major = deg % 30 == 0
                val len = (if (major) 9 else 5).dp.toPx()
                val a = Math.toRadians(deg.toDouble())
                val sx = kotlin.math.sin(a).toFloat(); val cy = -kotlin.math.cos(a).toFloat()
                val o = Offset(c.x + sx * r, c.y + cy * r)
                val i = Offset(c.x + sx * (r - len), c.y + cy * (r - len))
                val col = if (deg == 0) Palette.magenta else tick
                drawLine(shadow, o, i, 3f)
                drawLine(col, o, i, if (major) 2f else 1.2f)
                if (major) {
                    val label = if (deg == 0) "N" else (deg / 10).toString()
                    val layout = measurer.measure(label, if (deg == 0) northStyle else numStyle)
                    val lr = r + 8.dp.toPx()
                    val p = Offset(c.x + sx * lr - layout.size.width / 2f, c.y + cy * lr - layout.size.height / 2f)
                    rotate(deg.toFloat(), Offset(p.x + layout.size.width / 2f, p.y + layout.size.height / 2f)) {
                        drawText(layout, topLeft = p)
                    }
                }
            }
        }
        // Centre mark: what the position readout refers to.
        drawCircle(shadow, 4.dp.toPx(), c, style = Stroke(3f))
        drawCircle(Color.White.copy(alpha = 0.8f), 4.dp.toPx(), c, style = Stroke(1.5f))
    }
}

/** A chart scale bar for the ground at the screen centre: a round length, in km or m. */
@Composable
private fun ScaleBar(v: DoubleArray) {
    val mPerPx = v.getOrNull(4) ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val mPerDp = mPerPx * density
    if (v[2] > 3_000_000 || mPerDp <= 0) return // from far out the ground curves away: no single scale
    var len = 1.0
    val target = mPerDp * 110 // about 110 dp long
    while (len * 10 <= target) len *= 10
    len = when {
        len * 5 <= target -> len * 5
        len * 2 <= target -> len * 2
        else -> len
    }
    val widthDp = (len / mPerDp).toFloat()
    val label = if (len >= 1000) "${(len / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${len.toInt()} m"
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 3.dp)) {
        Canvas(Modifier.width(widthDp.dp).height(8.dp)) {
            val h = size.height
            val half = size.width / 2
            // Alternating black and white halves, as on a printed chart.
            drawRect(Color.Black, Offset(0f, h * 0.35f), androidx.compose.ui.geometry.Size(half, h * 0.65f))
            drawRect(Color.White, Offset(half, h * 0.35f), androidx.compose.ui.geometry.Size(half, h * 0.65f))
            drawRect(Color.White, Offset.Zero, size, style = Stroke(1.5f))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = Figures.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, active: Boolean, tint: Color = Palette.text, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(if (active) Palette.magenta.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) Palette.magenta else tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ToolDivider() {
    Box(Modifier.width(48.dp).height(1.dp).padding(horizontal = 10.dp).background(Palette.line))
}

/** Chart style: 35°47′32″N  5°49′37″W, then the height. */
private fun position(v: DoubleArray): String {
    fun dms(d: Double, pos: Char, neg: Char): String {
        val a = kotlin.math.abs(d)
        val deg = a.toInt()
        val minF = (a - deg) * 60
        val min = minF.toInt()
        val sec = ((minF - min) * 60).roundToInt().coerceAtMost(59)
        return "$deg°%02d′%02d″%s".format(Locale.ROOT, min, sec, if (d >= 0) pos else neg)
    }
    val alt = v[2].let { if (it < 10_000) "%.0f m".format(Locale.ROOT, it) else if (it < 1_000_000) "%.1f km".format(Locale.ROOT, it / 1000) else "%,d km".format(Locale.ROOT, (it / 1000).toLong()) }
    return "${dms(v[0], 'N', 'S')}   ${dms(v[1], 'E', 'W')}   $alt up"
}

@Composable
private fun LayerLine(dot: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Outlined: some layer colours (white aircraft) would vanish on chart paper.
        Box(Modifier.size(9.dp).background(dot, CircleShape).border(1.dp, Palette.text, CircleShape))
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
private fun Header(big: String?, bigColor: Color, title: String, sub: String, onClose: () -> Unit, sym: Sym? = null) {
    Row(verticalAlignment = Alignment.Top) {
        if (big != null) {
            Text(big, color = Palette.text, style = Figures.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(14.dp))
        } else if (sym != null) {
            Box(Modifier.padding(top = 4.dp, end = 10.dp)) { Symbol(sym, bigColor, 18.dp) }
        }
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Header(null, Palette.ship, sh.name ?: "MMSI ${sh.mmsi}", "MMSI ${sh.mmsi}", onClose, Sym.PLANE)
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
    Header(null, Palette.webcam, w.title, w.place ?: "", onClose, Sym.DIAMOND)
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
    Header(null, Palette.fire, "Fire detected by satellite", listOfNotNull(conf, if (h.day) "daytime pass" else "night pass").joinToString(", "), onClose, Sym.DOT)
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
    Header(null, Palette.text, f.callsign ?: f.hex.uppercase(), sub, onClose, Sym.PLANE)
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
    Header(null, Palette.satellite, s.tle.name, "NORAD ${s.tle.norad}, one orbit every ${s.tle.periodMin.roundToInt()} min", onClose, Sym.DIAMOND)
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
    Header(null, Palette.event(e.category), e.title, sub, onClose, Sym.DOT)
    LinkRow("From NASA EONET", if (e.url != null) "Open source" else null) { e.url?.let { openUrl(context, it) } }
}

@Composable
private fun ColumnScope.MeBody(state: EyeState, now: Long, onClose: () -> Unit) {
    val me = state.me
    Header(null, Palette.me, "You are here", if (me == null) "" else "%.4f, %.4f".format(me.latitude, me.longitude), onClose, Sym.YOU)
    if (me != null) {
        val acc = if (me.hasAccuracy()) "within ${me.accuracy.roundToInt()} m" else "accuracy unknown"
        Line("$acc, ${Fmt.ago(me.time, now).lowercase()}")
    }
    WeatherLines(state)
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun ColumnScope.PlaceBody(p: Sel.OfPlace, state: EyeState, onClose: () -> Unit) {
    Header(null, Palette.magenta, "Weather here", "%.4f, %.4f".format(p.lat, p.lon), onClose, Sym.DOT)
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

// ---- Today --------------------------------------------------------------------------------------

@Composable
private fun BriefPanel(state: EyeState, actions: Actions) {
    val b = state.brief
    val context = LocalContext.current
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(18.dp)),
    ) {
        val maxH = (LocalConfiguration.current.screenHeightDp - 110).coerceAtLeast(240)
        Column(Modifier.heightIn(max = maxH.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(state.clock)),
                    color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.brief(false) }) { Icon(Icons.Default.Close, "Close", tint = Palette.dim) }
            }
            TextButton(onClick = {
                if (b.editing) actions.reloadBrief() // leaving the settings: rebuild with them
                b.editing = !b.editing
            }, modifier = Modifier.padding(start = 0.dp)) {
                Text(if (b.editing) "Done, rebuild my brief" else "Customise", color = Palette.accent)
            }
            if (b.editing) BriefSettings(b.prefs, actions.briefPrefs)

            // Weather where you are.
            val w = b.weather
            when {
                w != null -> {
                    val d = b.forecast?.days?.firstOrNull()
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                        Text("${w.tempC.roundToInt()}°", color = Palette.text, style = Figures.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(w.description, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            if (d != null) {
                                Text(
                                    "High ${d.maxC.roundToInt()}°, low ${d.minC.roundToInt()}°" + (d.rainChance?.let { ", rain $it%" } ?: ""),
                                    color = Palette.dim, fontSize = 14.sp,
                                )
                            }
                        }
                    }
                    Text(weatherDetail(w), color = Palette.dim, fontSize = 13.sp)
                    b.forecast?.let { ForecastView(it) }
                }
                b.weatherProblem != null -> Text(b.weatherProblem!!, color = Palette.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                else -> Text("Getting the weather where you are…", color = Palette.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.line)

            // The written brief (Gemini), when there is a key: made on the first open of the day.
            val summary = b.summary
            if (state.keys.gemini.isEmpty()) {
                if (!b.loading) Text(
                    "Add a Google Gemini key in the menu, under API keys, for a written summary on top.",
                    color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp),
                )
            } else {
                if (summary != null) Text(summary, color = Palette.text, fontSize = 16.sp, lineHeight = 23.sp)
                else if (b.writing) Text("Writing today's summary…", color = Palette.dim, fontSize = 14.sp)
                b.summaryProblem?.let { Text(it, color = Palette.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        if (summary != null) "Written by Gemini from the headlines below" + (b.loadedAt.takeIf { it > 0 }?.let { ", ${clock(it)}" } ?: "") + "." else "",
                        color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    if (b.writing) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Palette.magenta, strokeWidth = 2.dp)
                    } else if (b.stories.isNotEmpty()) {
                        TextButton(onClick = actions.regenerateSummary) { Text(if (summary == null) "Write summary" else "Regenerate", color = Palette.accent) }
                    }
                }
            }

            if (b.loading) Text("Getting today's news…", color = Palette.dim, fontSize = 14.sp)
            for (p in b.newsProblems) Text(p, color = Palette.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (!b.loading && b.stories.isEmpty() && b.newsProblems.isEmpty() && b.loadedAt > 0) {
                Text("No stories from the last 24 hours.", color = Palette.dim, fontSize = 14.sp)
            }
            for (st in b.stories) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = st.link.isNotEmpty()) { openUrl(context, st.link) }
                        .padding(vertical = 9.dp),
                ) {
                    Text(st.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
                    if (st.summary.isNotEmpty()) Text(st.summary, color = Palette.dim, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        st.source + (st.timeMs?.let { ", " + Fmt.ago(it, state.clock).lowercase() } ?: ""),
                        color = Palette.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (!b.loading) {
                TextButton(onClick = actions.reloadBrief, modifier = Modifier.padding(top = 6.dp)) { Text("Refresh", color = Palette.accent) }
            }
        }
    }
}

/** What goes into the brief, and how the written summary reads. */
@Composable
private fun BriefSettings(p: BriefPrefs, set: (BriefPrefs) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Toggle("Weather where I am", p.weather, true) { set(p.copy(weather = it)) }

        Label("News sources")
        for ((topic, group) in News.SOURCES.groupBy { it.topic }) {
            Text(topic, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (src in group) {
                    FilterChip(
                        selected = src.id in p.sources,
                        onClick = { set(p.copy(sources = if (src.id in p.sources) p.sources - src.id else p.sources + src.id)) },
                        label = { Text(src.name) },
                    )
                }
            }
        }

        Label("My own feeds")
        for (url in p.custom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(url, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { set(p.copy(custom = p.custom - url)) }) { Text("Remove", color = Palette.error) }
            }
        }
        var newFeed by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFeed, onValueChange = { newFeed = it }, singleLine = true,
                placeholder = { Text("Feed address (RSS or Atom)", color = Palette.dim) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val u = newFeed.trim().let { if (it.startsWith("http")) it else "https://$it" }
                    if (u !in p.custom) set(p.copy(custom = p.custom + u))
                    newFeed = ""
                },
                enabled = newFeed.contains('.'),
            ) { Text("Add", color = Palette.accent) }
        }

        Label("Topics")
        TextSetting("Only stories about (words, comma separated)", p.include) { set(p.copy(include = it)) }
        TextSetting("Never stories about", p.exclude) { set(p.copy(exclude = it)) }

        Label("Number of stories")
        ChipRow(listOf(6, 12, 20), p.stories, { "$it" }, true) { set(p.copy(stories = it)) }

        Label("Written summary (needs a Gemini key)")
        ChipRow(listOf(3, 5, 8), p.length, { mapOf(3 to "Short", 5 to "Medium", 8 to "Detailed")[it]!! }, true) { set(p.copy(length = it)) }
        ChipRow(listOf(false, true), p.bullets, { if (it) "Bullet points" else "Paragraph" }, true) { set(p.copy(bullets = it)) }
        ChipRow(listOf("English", "French", "Arabic", "Spanish"), p.language, { it }, true) { set(p.copy(language = it)) }
        TextSetting("What should it focus on? (for example: Morocco, tech, markets)", p.focus) { set(p.copy(focus = it)) }
    }
}

/** A text preference saved as you type. */
@Composable
private fun TextSetting(hint: String, value: String, save: (String) -> Unit) {
    var text by remember(hint) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; save(it) },
        singleLine = true,
        placeholder = { Text(hint, color = Palette.dim, fontSize = 14.sp) },
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

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
            val maxH = (LocalConfiguration.current.screenHeightDp - 220).coerceIn(120, 420)
            Column(Modifier.heightIn(max = maxH.dp).verticalScroll(rememberScrollState())) {
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
private fun LayersPanel(
    s: Layers, change: (Layers) -> Unit, cacheBytes: Long?, clearCache: () -> Unit, keys: Keys, saveKeys: (Keys) -> Unit,
    allCredits: List<String>,
) {
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.widthIn(max = 340.dp).border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        // Fits the screen in both orientations, scrolling inside.
        val maxH = (LocalConfiguration.current.screenHeightDp - 110).coerceAtLeast(200)
        Column(Modifier.heightIn(max = maxH.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
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
            Section("Ships", "Live positions from AISStream, below 2,000 km. Needs a key.", s.ships) { change(s.copy(ships = it)) }

            Divider()
            Section("Webcams", "Windy webcams near the centre, below 1,000 km. Needs a key.", s.webcams) { change(s.copy(webcams = it)) }

            Divider()
            Section("Fire hotspots", "Every fire NASA satellites saw in the last 24 h. Needs a key.", s.fires) { change(s.copy(fires = it)) }

            Divider()
            Section("Where I am", "Your position, only while the app is open", s.location) { change(s.copy(location = it)) }

            Divider()
            Text("API keys", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Free, and kept on this phone only. Get a key opens the page where you sign up; paste the key here.",
                color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            KeyField("AISStream", "Ships", keys.ais, "https://aisstream.io/apikeys") { saveKeys(keys.copy(ais = it)) }
            KeyField("Windy Webcams", "Webcams", keys.windy, "https://api.windy.com/keys") { saveKeys(keys.copy(windy = it)) }
            KeyField("NASA FIRMS", "Fire hotspots", keys.firms, "https://firms.modaps.eosdis.nasa.gov/api/map_key/") { saveKeys(keys.copy(firms = it)) }
            KeyField("Google Gemini", "the written brief in Today", keys.gemini, "https://aistudio.google.com/apikey") { saveKeys(keys.copy(gemini = it)) }

            Divider()
            Toggle("Light panels", s.lightPanels, true) { change(s.copy(lightPanels = it)) }
            Toggle("Show map credits", s.credits, true) { change(s.copy(credits = it)) }
            Text(
                "Esri, RainViewer and OpenStreetMap require their names on the map once the app is public. " +
                    "Sources in use: " + allCredits.joinToString(", ") + ".",
                color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )

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

/** One personal key: what it unlocks, whether it is set, where to get it, and a field to paste it. */
@Composable
private fun KeyField(service: String, unlocks: String, saved: String, getUrl: String, save: (String) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(service, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (saved.isEmpty()) "Not set. Needed for $unlocks." else "Saved, ending ${saved.takeLast(4)}. Used for $unlocks.",
                    color = if (saved.isEmpty()) Palette.dim else Palette.text, fontSize = 13.sp,
                )
            }
            TextButton(onClick = { openUrl(context, getUrl) }) { Text("Get a key", color = Palette.accent) }
        }
        if (editing || saved.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("Paste the $service key", color = Palette.dim) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { save(text.trim()); text = ""; editing = false }, enabled = text.isNotBlank()) {
                    Text("Save", color = Palette.accent)
                }
            }
        } else {
            Row {
                TextButton(onClick = { editing = true }) { Text("Replace key", color = Palette.accent) }
                TextButton(onClick = { save("") }) { Text("Remove", color = Palette.error) }
            }
        }
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
