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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.verisonder.sondereye.core.Fmt
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Period
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.data.QuakeSettings
import com.verisonder.sondereye.globe.GlobeView
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class Actions(
    val refresh: () -> Unit,
    val change: (QuakeSettings) -> Unit,
    val select: (Quake?) -> Unit,
    val home: () -> Unit,
)

private class LastQuake { var quake: Quake? = null }

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

    BackHandler(enabled = state.layersOpen || state.selected != null) {
        if (state.layersOpen) state.layersOpen = false else actions.select(null)
    }

    Box(Modifier.fillMaxSize().background(Palette.space)) {
        if (globeView != null) {
            AndroidView(
                factory = { globeView.also { v -> (v.parent as? ViewGroup)?.removeView(v) } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Required by the imagery provider. Sits under the quake card when one is open.
        Text(
            "Imagery: Esri, Maxar, Earthstar Geographics",
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
            StatusCard(state, now, actions, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(Icons.Default.Menu, "Layers") { state.layersOpen = !state.layersOpen }
                if (state.loading) {
                    Box(Modifier.size(44.dp).background(Palette.panel, CircleShape), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
                    }
                } else {
                    RoundButton(Icons.Default.Refresh, "Refresh", actions.refresh)
                }
                RoundButton(Icons.Default.Home, "Whole Earth", actions.home)
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
            // Keeps showing the last quake while the card slides out.
            val last = remember { LastQuake() }
            state.selected?.let { last.quake = it }
            last.quake?.let { QuakeCard(it, now) { actions.select(null) } }
        }

        if (state.layersOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.layersOpen = false } })
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = 12.dp, end = 66.dp, start = 12.dp)
            ) {
                LayersPanel(state.settings, actions.change)
            }
        }
    }
}

@Composable
private fun StatusCard(state: EyeState, now: Long, actions: Actions, modifier: Modifier) {
    val s = state.settings
    Column(
        modifier
            .background(Palette.panel, PanelShape)
            .border(1.dp, Palette.line, PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val n = state.quakes.size
        val title = when {
            !s.enabled -> "Earthquakes off"
            state.loading && state.updatedAt == null -> "Loading earthquakes…"
            else -> if (n == 1) "1 earthquake" else "$n earthquakes"
        }
        Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        if (s.enabled && state.updatedAt != null) {
            val detail = buildString {
                append("${s.minMag.label}, ${s.period.label.lowercase()}")
                append(if (state.loading) ", refreshing…" else ", updated ${clock(state.updatedAt!!)}")
                if (state.skipped > 0) append(", ${state.skipped} unreadable skipped")
            }
            Text(detail, color = Palette.dim, fontSize = 13.sp)
        }

        val g = state.globeStatus
        if (g != null && g.failures == 0 && g.loading > 0) {
            Text("Loading imagery, ${g.loading} tiles…", color = Palette.dim, fontSize = 13.sp)
        }

        state.quakeError?.let {
            Text(
                "$it. Tap to retry.",
                color = Palette.error, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = actions.refresh),
            )
        }
        if (g != null && g.failures > 0) {
            Text(
                "Imagery: ${g.failures} tiles failed (${g.lastFailure}). Retrying every 20 s.",
                color = Palette.error, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.globeError?.let {
            Text(it, color = Palette.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(Palette.panel, CircleShape)
            .border(1.dp, Palette.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Palette.text) }
    }
}

@Composable
private fun QuakeCard(q: Quake, now: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    Fmt.mag(q.mag),
                    color = Palette.depth(q.depthKm),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(q.place, color = Palette.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.ago(q.timeMs, now)}, ${Fmt.depth(q.depthKm)}",
                        color = Palette.dim, fontSize = 13.sp,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Palette.dim) }
            }
            Fmt.typeLabel(q.type)?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
            if (q.tsunami) {
                // USGS sets this flag for large oceanic events. It is not a warning itself.
                Text("Large oceanic event: check tsunami.gov for warnings", color = Palette.error, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fullTime(q.timeMs), color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (q.url.isNotEmpty()) {
                    TextButton(onClick = { openUrl(context, q.url) }) {
                        Text("Open on USGS", color = Palette.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun LayersPanel(s: QuakeSettings, change: (QuakeSettings) -> Unit) {
    Surface(
        color = Palette.panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.widthIn(max = 340.dp).border(1.dp, Palette.line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Earthquakes", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("From USGS, updated every minute", color = Palette.dim, fontSize = 12.sp)
                }
                Switch(checked = s.enabled, onCheckedChange = { change(s.copy(enabled = it)) })
            }

            Column(Modifier.alpha(if (s.enabled) 1f else 0.4f)) {
                Label("Minimum magnitude")
                ChipRow(MinMag.entries, s.minMag, { it.label }, s.enabled) { change(s.copy(minMag = it)) }

                Label("Period")
                ChipRow(Period.entries, s.period, { it.label }, s.enabled) { change(s.copy(period = it)) }

                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Refresh every 5 min", color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.autoRefresh,
                        enabled = s.enabled,
                        onCheckedChange = { change(s.copy(autoRefresh = it)) },
                    )
                }

                Label("Colour is depth, size is magnitude")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Palette.shallow, "0–70 km")
                    Legend(Palette.mid, "70–300 km")
                    Legend(Palette.deep, "300+ km")
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
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

private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun fullTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
