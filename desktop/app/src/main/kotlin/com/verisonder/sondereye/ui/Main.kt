package com.verisonder.sondereye.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** SonderEye for Windows: one window, the globe and everything on it, as on the phone. */
fun main() {
    // A crash leaves its story in %LOCALAPPDATA%\SonderEye\files\crash.log.
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching {
            java.io.File(AppDirs.files, "crash.log").appendText(
                "${java.time.Instant.now()} on ${t.name}: ${e.stackTraceToString()}\n",
            )
        }
    }
    window()
}

private fun window() = application {
    // The app outlives its window: switching full screen on or off makes a new window
    // (a title bar can only be added or removed before a window is shown).
    val scope = rememberCoroutineScope()
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val scale = screen.defaultTransform.scaleX.toFloat()
    // The phone's layout, a quarter larger: a PC screen is further from the eye.
    val density = scale * UI_SCALE
    val controller = remember { MainActivity(scope, density).also { it.start(fresh = true) } }
    var fullscreen by remember { mutableStateOf(true) }
    key(fullscreen) {
        // Full screen: no title bar, the whole screen, taskbar included. Otherwise a normal window.
        val bounds = screen.bounds
        val state = if (fullscreen) {
            rememberWindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(bounds.x.dp, bounds.y.dp),
                size = DpSize(bounds.width.dp, bounds.height.dp),
            )
        } else {
            rememberWindowState(placement = WindowPlacement.Maximized, width = 1440.dp, height = 900.dp)
        }
        Window(
            onCloseRequest = {
                controller.close()
                exitApplication()
            },
            title = "SonderEye",
            icon = painterResource("icon.png"),
            state = state,
            undecorated = fullscreen,
            resizable = !fullscreen,
            onPreviewKeyEvent = { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.F11 -> { fullscreen = !fullscreen; true }
                    // Escape does what the phone's back button does: closes the panel or card on top.
                    e.key == Key.Escape -> Back.press()
                    else -> false
                }
            },
            // Not "preview": a text box being typed in gets these keys first and keeps them.
            onKeyEvent = { e ->
                val down = e.type == KeyEventType.KeyDown
                if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) return@Window false
                when (e.key) {
                    Key.W, Key.DirectionUp -> { controller.keyMove(0, -1, down); true }
                    Key.S, Key.DirectionDown -> { controller.keyMove(0, 1, down); true }
                    Key.A, Key.DirectionLeft -> { controller.keyMove(-1, 0, down); true }
                    Key.D, Key.DirectionRight -> { controller.keyMove(1, 0, down); true }
                    Key.E -> { if (down) controller.keyNearest(); true }
                    Key.Enter, Key.NumPadEnter -> { if (down) controller.keyOpen(); true }
                    else -> false
                }
            },
        ) {
            val base = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density * UI_SCALE, base.fontScale),
                    LocalConfiguration provides Configuration((maxWidth.value / UI_SCALE).toInt(), (maxHeight.value / UI_SCALE).toInt()),
                ) {
                    controller.Content()
                }
            }
        }
    }
}

/** How much larger than on the phone everything is drawn. */
private const val UI_SCALE = 1.25f
