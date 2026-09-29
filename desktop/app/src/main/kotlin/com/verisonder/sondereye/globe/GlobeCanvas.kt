package com.verisonder.sondereye.globe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import com.verisonder.sondereye.core.EARTH_R
import com.verisonder.sondereye.core.Geo
import com.verisonder.sondereye.core.V3
import com.verisonder.sondereye.core.View
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The globe on screen, and the mouse on it:
 * drag to move, wheel to zoom at the pointer, right-drag (or middle-drag) to turn,
 * click to pick, double-click to zoom in, right-click or a still one-second press for
 * what is at that spot.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlobeCanvas(g: GlobeView, modifier: Modifier = Modifier) {
    val painter = remember(g) { Painter(g) }
    val scope = rememberCoroutineScope()
    val mouse = remember { MouseState() }
    // Drawing paths reused frame after frame: a new one each time is native memory Java is slow to give back.
    val scratch = remember { Scratch() }
    val lineCache = remember { LineCache() }
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { g.width = it.width; g.height = it.height }
            .onPointerEvent(PointerEventType.Press) { e ->
                val p = e.changes.first().position
                g.pressed()
                mouse.downX = p.x; mouse.downY = p.y; mouse.lastX = p.x; mouse.lastY = p.y
                mouse.moved = false
                mouse.turning = e.buttons.isSecondaryPressed || e.buttons.isTertiaryPressed
                mouse.secondary = e.buttons.isSecondaryPressed
                mouse.held = false
                mouse.hold?.cancel()
                if (!mouse.turning) mouse.hold = scope.launch {
                    delay(1_000)
                    if (!mouse.moved) {
                        mouse.held = true
                        g.hold(mouse.downX, mouse.downY)
                    }
                }
            }
            .onPointerEvent(PointerEventType.Move) { e ->
                val ch = e.changes.first()
                if (!ch.pressed) return@onPointerEvent
                val p = ch.position
                if (!mouse.moved && hypot((p.x - mouse.downX).toDouble(), (p.y - mouse.downY).toDouble()) > 4.0) {
                    mouse.moved = true
                    mouse.hold?.cancel()
                    g.moved()
                }
                if (mouse.moved) {
                    if (mouse.turning) {
                        // Turning: the angle round the screen centre, followed.
                        val cx = g.width / 2.0
                        val cy = g.height / 2.0
                        val a0 = atan2(mouse.lastY - cy, mouse.lastX - cx)
                        val a1 = atan2(p.y - cy, p.x - cx)
                        g.turn(-Geo.wrapLon(Geo.toDeg(a1 - a0)))
                    } else {
                        g.drag(mouse.lastX, mouse.lastY, p.x, p.y)
                    }
                }
                mouse.lastX = p.x; mouse.lastY = p.y
            }
            .onPointerEvent(PointerEventType.Release) { e ->
                val p = e.changes.first().position
                mouse.hold?.cancel()
                if (mouse.moved || mouse.held) return@onPointerEvent
                if (mouse.secondary) {
                    g.hold(p.x, p.y)
                    return@onPointerEvent
                }
                val now = System.currentTimeMillis()
                if (now - mouse.lastClick < 350 && hypot((p.x - mouse.clickX).toDouble(), (p.y - mouse.clickY).toDouble()) < 8) {
                    mouse.lastClick = 0
                    g.doubleClick(p.x, p.y)
                } else {
                    mouse.lastClick = now
                    mouse.clickX = p.x; mouse.clickY = p.y
                    g.click(p.x, p.y)
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val ch = e.changes.first()
                val d = ch.scrollDelta
                val p = ch.position
                g.pressed()
                g.moved()
                // A touchpad pinch arrives as Ctrl + scroll: zoom. A touchpad's two-finger slide
                // arrives as fine or sideways scrolling: move the map with the fingers. A mouse
                // wheel turns in whole notches, straight up or down: zoom.
                val pinch = e.keyboardModifiers.isCtrlPressed
                val wheel = d.x == 0f && d.y != 0f && d.y == kotlin.math.round(d.y)
                if (pinch || wheel) {
                    g.zoom(exp(-d.y * if (pinch) 0.12f else 0.22f).toDouble(), p.x, p.y)
                } else {
                    g.drag(p.x, p.y, p.x - d.x * SLIDE_PX, p.y - d.y * SLIDE_PX)
                }
            },
    ) {
        // Reading the state here makes the canvas draw again when any of it changes.
        g.tilesVersion
        val v = g.cam.view(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        drawRect(Color(Painter.SPACE))
        if (g.hold) return@Canvas
        drawGlow(v)
        drawIntoCanvas { painter.draw(it.nativeCanvas, v, g.density) }
        drawLines(v, g.lines, lineCache)
        g.highlight?.let { hl -> drawIntoCanvas { painter.drawHighlight(it.nativeCanvas, v, g.density, hl) } }
        drawMarkers(v, g.markers, g.selectedKey, g.density, scratch)
    }
}

/** Pixels the map moves per unit of touchpad scrolling. */
private const val SLIDE_PX = 40f

private class Scratch {
    val a = Path()
    val b = Path()
}

private class MouseState {
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var moved = false
    var turning = false
    var secondary = false
    var held = false
    var hold: Job? = null
    var lastClick = 0L
    var clickX = 0f
    var clickY = 0f
}

/** The thin blue air round the Earth. */
private fun DrawScope.drawGlow(v: View) {
    val r = v.earthRadiusPx().toFloat()
    if (r <= 0f || r > size.maxDimension * 4) return
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(
        Brush.radialGradient(
            0f to Color(0x00000000), 0.88f to Color(0x00000000),
            0.905f to Color(0x6650A0FF), 1f to Color(0x00000000),
            center = c, radius = r * 1.1f,
        ),
        radius = r * 1.1f, center = c,
    )
}

/** Is [p] seen from the camera: on this side of the horizon, or in orbit and not behind the Earth. */
internal fun visible(v: View, p: V3): Boolean {
    val len = p.len()
    if (len < EARTH_R + 50_000.0) return v.aboveHorizon(p)
    val d = p - v.eye
    val dist = d.len()
    val dir = d * (1.0 / dist)
    val b = v.eye dot dir
    val c = (v.eye dot v.eye) - EARTH_R * EARTH_R
    val disc = b * b - c
    if (disc < 0) return true
    val t = -b - sqrt(disc)
    return t < 0 || t > dist
}

/**
 * Lines (bus routes, trails, orbits). A city's bus network is tens of thousands of segments,
 * so: lines of one colour go into one path, points are projected without allocating, the
 * far side and off-screen segments are skipped, and the paths are kept while the camera and
 * the lines stay the same (the globe also redraws for other reasons, every second).
 */
private fun DrawScope.drawLines(v: View, lines: List<GlobeLine>, cache: LineCache) {
    if (cache.cam != v.cam || cache.w != v.width || cache.h != v.height || cache.lines !== lines) {
        cache.rebuild(v, lines)
    }
    for ((color, path) in cache.paths) drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

internal class LineCache {
    var cam: com.verisonder.sondereye.core.CameraState? = null
    var w = 0
    var h = 0
    var lines: List<GlobeLine>? = null
    var paths: List<Pair<Color, Path>> = emptyList()
    private val pool = ArrayList<Path>()

    private var sx = 0f
    private var sy = 0f

    /** Screen position of world point (x, y, z) into [sx], [sy]; false when not seen. */
    private fun project(v: View, x: Double, y: Double, z: Double): Boolean {
        val e = v.eye
        val r2 = x * x + y * y + z * z
        val pe = x * e.x + y * e.y + z * e.z
        if (r2 < ORBIT2) {
            if (pe <= r2) return false // beyond the horizon
        } else if (!visible(v, V3(x, y, z))) return false
        val dx = x - e.x
        val dy = y - e.y
        val dz = z - e.z
        val f = v.forward
        val d = dx * f.x + dy * f.y + dz * f.z
        if (d <= 0) return false
        val r = v.right
        val u = v.up
        sx = (v.width / 2.0 + (dx * r.x + dy * r.y + dz * r.z) / d * v.focalPx).toFloat()
        sy = (v.height / 2.0 - (dx * u.x + dy * u.y + dz * u.z) / d * v.focalPx).toFloat()
        return true
    }

    private fun off(x: Float, y: Float) = x < -100 || y < -100 || x > w + 100 || y > h + 100

    fun rebuild(v: View, list: List<GlobeLine>) {
        cam = v.cam; w = v.width; h = v.height; lines = list
        val groups = LinkedHashMap<Long, Path>()
        var used = 0
        fun pathFor(key: Long): Path = groups.getOrPut(key) {
            (if (used < pool.size) pool[used] else Path().also { pool.add(it) }).also { used++; it.reset() }
        }
        for (l in list) {
            val key = (l.rgb.toLong() shl 8) or (l.alpha * 255).toLong()
            val path = pathFor(key)
            val pts = l.points
            if (l.pairs) {
                var i = 0
                while (i + 1 < pts.size) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    i += 2
                    if (!project(v, a.x, a.y, a.z)) continue
                    val ax = sx
                    val ay = sy
                    if (!project(v, b.x, b.y, b.z)) continue
                    if (off(ax, ay) && off(sx, sy)) continue
                    path.moveTo(ax, ay)
                    path.lineTo(sx, sy)
                }
            } else {
                var open = false
                for (p in pts) {
                    if (!project(v, p.x, p.y, p.z)) { open = false; continue }
                    if (!open) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                    open = true
                }
            }
        }
        paths = groups.map { (k, p) -> Color(((k shr 8).toInt()) or (0xFF shl 24)).copy(alpha = (k and 0xFF) / 255f) to p }
    }

    companion object {
        private const val ORBIT2 = (EARTH_R + 50_000.0) * (EARTH_R + 50_000.0)
    }
}

private val DARK = Color(0xE6030A10)

/** Markers face the screen: dots, arrows for aircraft, ships and buses, diamonds for satellites. */
private fun DrawScope.drawMarkers(v: View, list: List<Marker>, selected: String?, density: Float, scratch: Scratch) {
    var sel: Pair<Offset, Float>? = null
    for (m in list) {
        if (!visible(v, m.pos)) continue
        val s = v.project(m.pos) ?: continue
        val c = Offset(s[0].toFloat(), s[1].toFloat())
        if (c.x < -50 || c.y < -50 || c.x > size.width + 50 || c.y > size.height + 50) continue
        val r = m.sizePx / 2
        val color = Color(m.rgb or 0xFF000000.toInt())
        when (m.shape) {
            Marker.SHAPE_PLANE -> {
                val ang = screenBearing(v, m)
                rotate(ang, pivot = c) {
                    val body = scratch.a.apply {
                        reset()
                        moveTo(c.x, c.y - r * 0.9f)
                        lineTo(c.x + r * 0.62f, c.y + r * 0.6f)
                        lineTo(c.x, c.y + r * 0.2f)
                        lineTo(c.x - r * 0.62f, c.y + r * 0.6f)
                        close()
                    }
                    drawPath(body, DARK, style = Stroke(width = 2.2f * density / 1.5f))
                    drawPath(body, color)
                }
            }
            Marker.SHAPE_SAT -> {
                val d = scratch.a.apply {
                    reset()
                    moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
                }
                drawPath(d, DARK)
                val d2 = scratch.b.apply {
                    reset()
                    val q = r * 0.72f
                    moveTo(c.x, c.y - q); lineTo(c.x + q, c.y); lineTo(c.x, c.y + q); lineTo(c.x - q, c.y); close()
                }
                drawPath(d2, color)
            }
            Marker.SHAPE_ME -> {
                drawCircle(color.copy(alpha = 0.22f), r, c)
                drawCircle(Color.White, r * 0.58f, c)
                drawCircle(color, r * 0.42f, c)
            }
            else -> {
                drawCircle(DARK, r, c)
                drawCircle(color, (r - 1.2f * density).coerceAtLeast(1f), c)
            }
        }
        if (m.key == selected) sel = c to r
    }
    sel?.let { (c, r) ->
        drawCircle(Color.White, r + 7f * density, c, style = Stroke(width = 2f * density))
    }
}

/** A marker's bearing turned into screen degrees (north is wherever the map puts it). */
private fun screenBearing(v: View, m: Marker): Float {
    if (m.bearing.isNaN()) return 0f
    val a = v.project(m.pos) ?: return 0f
    val north = Geo.ecef(m.lat + 0.01, m.lon, m.altM)
    val b = v.project(north) ?: return m.bearing.toFloat()
    val up = Geo.toDeg(atan2(b[0] - a[0], -(b[1] - a[1])))
    return (up + m.bearing).toFloat()
}
