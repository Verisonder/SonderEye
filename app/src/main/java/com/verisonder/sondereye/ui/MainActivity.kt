package com.verisonder.sondereye.ui

import android.app.ActivityManager
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.data.QuakeSettings
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.data.UsgsClient
import com.verisonder.sondereye.globe.GlobeStatus
import com.verisonder.sondereye.globe.GlobeView
import com.verisonder.sondereye.globe.Marker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Everything the screen shows. Plain Compose state; the activity survives rotation (manifest). */
class EyeState {
    var settings by mutableStateOf(QuakeSettings())
    var quakes by mutableStateOf<List<Quake>>(emptyList())
    var skipped by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var updatedAt by mutableStateOf<Long?>(null)
    var quakeError by mutableStateOf<String?>(null)
    var globeStatus by mutableStateOf<GlobeStatus?>(null)
    var globeError by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Quake?>(null)
    var layersOpen by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {

    private val state = EyeState()
    private lateinit var store: Settings
    private var globe: GlobeView? = null
    private var fetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Settings(this)
        state.settings = store.load()
        installHttpCache()

        val gl = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
        if (gl >= 0x30000) {
            globe = GlobeView(this, object : GlobeView.Listener {
                override fun onTap(index: Int) {
                    state.selected = state.quakes.getOrNull(index)
                }
                override fun onStatus(status: GlobeStatus) {
                    state.globeStatus = status
                }
                override fun onError(message: String) {
                    state.globeError = message
                }
            })
        } else {
            state.globeError = "Globe: this phone reports OpenGL ES ${gl shr 16}.${gl and 0xFFFF}; 3.0 is required"
        }

        setContent {
            EyeTheme {
                EyeScreen(
                    state = state,
                    globeView = globe,
                    actions = Actions(
                        refresh = ::refresh,
                        change = ::change,
                        select = { q ->
                            state.selected = q
                            globe?.select(if (q == null) -1 else state.quakes.indexOf(q), fly = q != null)
                        },
                        home = {
                            state.selected = null
                            globe?.select(-1, fly = false)
                            globe?.home()
                        },
                    ),
                )
            }
        }

        refresh()

        // Auto-refresh only while the app is on screen: nothing runs in the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val s = state.settings
                    val age = System.currentTimeMillis() - (state.updatedAt ?: 0L)
                    if (s.enabled && s.autoRefresh && !state.loading && age >= AUTO_MS) refresh()
                    delay(30_000)
                }
            }
        }
    }

    private fun change(new: QuakeSettings) {
        val old = state.settings
        state.settings = new
        store.save(new)
        // Only a change to what is fetched needs a download.
        if (new.enabled != old.enabled || new.minMag != old.minMag || new.period != old.period) refresh()
    }

    private fun refresh() {
        fetchJob?.cancel()
        val s = state.settings
        if (!s.enabled) {
            state.loading = false
            state.quakes = emptyList()
            state.quakeError = null
            state.selected = null
            globe?.setMarkers(emptyList())
            return
        }
        state.loading = true
        fetchJob = lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { UsgsClient.fetch(s.minMag, s.period) }
            state.loading = false
            when (out) {
                is UsgsClient.Outcome.Ok -> {
                    val list = out.result.quakes
                    state.quakes = list
                    state.skipped = out.result.skipped
                    state.updatedAt = System.currentTimeMillis()
                    state.quakeError = null
                    // The selection survives a refresh if the quake is still in the feed.
                    state.selected = state.selected?.let { sel -> list.firstOrNull { it.id == sel.id } }
                    globe?.setMarkers(withContext(Dispatchers.Default) { markers(list) })
                    globe?.select(state.selected?.let { list.indexOf(it) } ?: -1, fly = false)
                }
                // Old markers stay on the globe; the error says the data is not current.
                is UsgsClient.Outcome.Failed -> state.quakeError = out.message
            }
        }
    }

    /** Size is magnitude, colour is depth; the same scale as the legend. */
    private fun markers(list: List<Quake>): List<Marker> {
        val d = resources.displayMetrics.density
        return list.map { q ->
            val size = (4.0 + (q.mag ?: 0.0) * 2.4).coerceIn(4.0, 24.0).toFloat() * d
            Marker(q.lat, q.lon, size, Palette.depth(q.depthKm).toArgb())
        }
    }

    private fun installHttpCache() {
        if (HttpResponseCache.getInstalled() != null) return
        try {
            HttpResponseCache.install(File(cacheDir, "http"), 150L * 1024 * 1024)
        } catch (e: IOException) {
            // Works without a cache, only slower on revisits.
            Log.w("SonderEye", "HTTP cache unavailable", e)
        }
    }

    override fun onResume() {
        super.onResume()
        globe?.onResume()
    }

    override fun onPause() {
        globe?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        globe?.release()
        HttpResponseCache.getInstalled()?.flush()
        super.onDestroy()
    }

    companion object {
        private const val AUTO_MS = 5 * 60_000L
    }
}
