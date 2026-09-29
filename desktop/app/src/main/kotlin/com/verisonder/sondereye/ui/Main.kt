package com.verisonder.sondereye.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** SonderEye for Windows: one window, the globe and everything on it, as on the phone. */
fun main() = application {
    val app = remember { arrayOfNulls<MainActivity>(1) }
    val window = rememberWindowState(width = 1440.dp, height = 900.dp)
    Window(
        onCloseRequest = {
            app[0]?.close()
            exitApplication()
        },
        title = "SonderEye",
        icon = painterResource("icon.png"),
        state = window,
        // Escape does what the phone's back button does: closes the panel or card on top.
        onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && Back.press() },
    ) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        val controller = remember {
            MainActivity(scope, density).also {
                it.start(fresh = true)
                app[0] = it
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                controller.Content()
            }
        }
    }
}
