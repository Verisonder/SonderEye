package com.verisonder.sondereye.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Desktop
import java.io.File
import java.net.URI

/*
 * The few Android things the app's screens use, done the Windows way, under the same names,
 * so the screens read the same as on the phone.
 */

/** Stands in for Android's Context where the screens pass one along (to open links). */
object DesktopContext

typealias Context = DesktopContext

val LocalContext = staticCompositionLocalOf { DesktopContext }

/** The window's size in dp, under the name the screens use on the phone. */
class Configuration(val screenWidthDp: Int, val screenHeightDp: Int)

val LocalConfiguration = compositionLocalOf { Configuration(1280, 800) }

/** Opens [url] in the default browser. */
fun openUrl(@Suppress("UNUSED_PARAMETER") context: Context, url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** A short message at the bottom of the window, gone after two seconds (Android's toast). */
object Toasts {
    var text by mutableStateOf<String?>(null)
    var at by mutableStateOf(0L)

    fun show(t: String) {
        text = t
        at = System.currentTimeMillis()
    }
}

/**
 * Escape does what Android's back button does. Screens register with the same call they
 * use on the phone; the newest enabled one handles it.
 */
object Back {
    private val handlers = ArrayList<Pair<() -> Boolean, () -> Unit>>()

    fun register(enabled: () -> Boolean, onBack: () -> Unit): Pair<() -> Boolean, () -> Unit> =
        (enabled to onBack).also { handlers.add(it) }

    fun unregister(h: Pair<() -> Boolean, () -> Unit>) = handlers.remove(h)

    /** True when something took the key. */
    fun press(): Boolean {
        val h = handlers.lastOrNull { it.first() } ?: return false
        h.second()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val on by rememberUpdatedState(enabled)
    val act by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val h = Back.register({ on }, { act() })
        onDispose { Back.unregister(h) }
    }
}

/** Where SonderEye keeps its files on this PC: %LOCALAPPDATA%\SonderEye. */
object AppDirs {
    private val root: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".sondereye")
        File(base, "SonderEye").also { it.mkdirs() }
    }
    val cache: File by lazy { File(root, "cache").also { it.mkdirs() } }
    val files: File by lazy { File(root, "files").also { it.mkdirs() } }
}
