package com.verisonder.sondereye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondereye.R

/*
 * The start-up sequence, about two seconds; a tap skips to the end.
 *   0.00–0.24 s  a dark tube warms up: one bright line grows across the middle
 *   0.24–0.46 s  the line opens into a screen, top and bottom edges glowing
 *   0.46–1.85 s  the mark comes up, then the boot lines type themselves out
 *   1.85–2.30 s  the screen fades, and the globe (already flying in) shows through
 */
private const val LINE_MS = 240L
private const val OPEN_MS = 220L
private const val TEXT_START = 460L
private const val TYPE_START = 760L
private const val LINE_GAP = 190L
private const val TYPE_MS = 150L
private const val FADE_START = 1_850L
private const val FADE_MS = 450L
private const val END = FADE_START + FADE_MS
/** The globe starts drawing this long before the fade, so its first frame is ready under it. */
private const val REVEAL_EARLY_MS = 150L

private val LINES = listOf("BOOTING GLOBE", "LINKING SATELLITES", "CALIBRATING RADAR", "TUNING THE FEEDS", "OPENING THE WORLD")

/** The tube's glass, before and between the glow. */
private val Glass = Color(0xFF03130A)

private fun ease(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).let { it * it * it }

/**
 * [onReveal] comes just before the fade, so the globe (drawing nothing until then) is there
 * to show through; [onDone] when it has gone.
 */
@Composable
fun BootSequence(onReveal: () -> Unit, onDone: () -> Unit) {
    var ms by remember { mutableLongStateOf(0L) }
    var skip by remember { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    val reveal by rememberUpdatedState(onReveal)
    LaunchedEffect(Unit) {
        val t0 = withFrameMillis { it }
        var offset = 0L
        var revealed = false
        while (true) {
            val now = withFrameMillis { it } - t0
            if (skip && now + offset < FADE_START) offset = FADE_START - now
            ms = now + offset
            if (!revealed && ms >= FADE_START - REVEAL_EARLY_MS) {
                revealed = true
                reveal()
            }
            if (ms >= END) break
        }
        reveal()
        done()
    }
    val g = Palette.signal
    val fade = if (ms < FADE_START) 1f else (1f - (ms - FADE_START) / FADE_MS.toFloat()).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { skip = true } }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val line = 2.dp.toPx()
            if (ms < LINE_MS) {
                // Still dark; a single line grows from the centre, with a soft glow round it.
                drawRect(Color.Black, size = size)
                val half = w * ease(ms / LINE_MS.toFloat()) / 2f
                drawRect(g.copy(alpha = 0.35f), Offset(w / 2f - half, cy - 6.dp.toPx()), Size(half * 2f, 12.dp.toPx()))
                drawRect(Color.White, Offset(w / 2f - half, cy - line / 2f), Size(half * 2f, line))
                return@Canvas
            }
            // The line opens into a screen.
            val open = ease((ms - LINE_MS) / OPEN_MS.toFloat())
            val ph = line + (h - line) * open
            val top = cy - ph / 2f
            // Solid: nothing behind shows until the fade.
            drawRect(Glass.copy(alpha = fade), Offset(0f, top), Size(w, ph))
            if (open < 1f) {
                val edge = g.copy(alpha = 1f - open)
                drawRect(edge, Offset(0f, top), Size(w, line))
                drawRect(edge, Offset(0f, top + ph - line), Size(w, line))
            }
            // Scanlines, and a faint bright band rolling down the tube.
            val step = 3.dp.toPx()
            val thin = 1.dp.toPx() * 0.6f
            var y = top
            while (y < top + ph) {
                drawRect(Color.Black.copy(alpha = 0.22f * fade), Offset(0f, y), Size(w, thin))
                y += step
            }
            val bandH = 140.dp.toPx()
            val band = ((ms % 1_600L) / 1_600f) * (h + bandH) - bandH / 2f
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, g.copy(alpha = 0.07f * fade), Color.Transparent), band - bandH / 2f, band + bandH / 2f),
                Offset(0f, band - bandH / 2f), Size(w, bandH),
            )
        }

        if (ms >= TEXT_START) {
            val up = ease((ms - TEXT_START) / 320f)
            Column(
                Modifier.align(Alignment.Center).alpha(fade).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(150.dp).graphicsLayer {
                        alpha = up
                        scaleX = 0.82f + 0.18f * up
                        scaleY = 0.82f + 0.18f * up
                    },
                )
                Text("SONDEREYE", color = g, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(up))
                Text("VERISONDER", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.alpha(up))
                Spacer(Modifier.height(22.dp))
                Column(Modifier.width(250.dp)) {
                    for ((i, label) in LINES.withIndex()) {
                        val start = TYPE_START + i * LINE_GAP
                        if (ms < start) continue // not reached yet
                        val typed = ((ms - start).toFloat() / TYPE_MS).coerceIn(0f, 1f)
                        val shown = "> " + label.take((label.length * typed).toInt())
                        val last = i == LINES.lastIndex
                        val finished = typed >= 1f
                        // A block cursor on the line being typed, and on the last one, blinking.
                        val cursor = if ((!finished || last) && (ms / 260) % 2 == 0L) " \u2588" else ""
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(shown + cursor, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (finished && !last) Text("OK", color = g, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
