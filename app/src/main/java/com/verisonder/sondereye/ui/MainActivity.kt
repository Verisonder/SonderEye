package com.verisonder.sondereye.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.verisonder.sondereye.core.Globe
import com.verisonder.sondereye.core.Quake
import com.verisonder.sondereye.data.QuakeSettings
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.data.UsgsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screen shows. Plain Compose state; the activity survives rotation (manifest). */
class EyeState {
    var settings by mutableStateOf(QuakeSettings())
    var quakes by mutableStateOf<List<Quake>>(emptyList())
    var skipped by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var updatedAt by mutableStateOf<Long?>(null)
    var quakeError by mutableStateOf<String?>(null)
    var globeReady by mutableStateOf(false)
    var globeError by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Quake?>(null)
    var layersOpen by mutableStateOf(false)
    /** Bumped when the WebView is replaced, so Compose swaps the view it shows. */
    var globeGeneration by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {

    private val state = EyeState()
    private lateinit var store: Settings
    private lateinit var globe: GlobeController
    private var fetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Settings(this)
        state.settings = store.load()

        globe = GlobeController(
            context = this,
            onReady = {
                state.globeReady = true
                state.globeError = null
            },
            onSelect = { id -> state.selected = id?.let { i -> state.quakes.firstOrNull { it.id == i } } },
            onError = { msg ->
                state.globeError = msg
                if (msg.startsWith("Globe crashed") || msg.startsWith("Globe was stopped")) state.globeReady = false
            },
        )

        setContent {
            EyeTheme {
                EyeScreen(
                    state = state,
                    globeView = { globe.webView },
                    actions = Actions(
                        refresh = ::refresh,
                        change = ::change,
                        select = { q ->
                            state.selected = q
                            globe.select(q?.id)
                        },
                        home = {
                            state.selected = null
                            globe.home()
                        },
                        restartGlobe = ::restartGlobe,
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
            globe.setQuakes("[]")
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
                    globe.setQuakes(withContext(Dispatchers.Default) { Globe.quakePayload(list) })
                    if (state.selected == null) globe.select(null)
                }
                // Old markers stay on the globe; the error says the data is not current.
                is UsgsClient.Outcome.Failed -> state.quakeError = out.message
            }
        }
    }

    private fun restartGlobe() {
        state.globeReady = false
        state.globeError = null
        globe.rebuild()
        state.globeGeneration++
    }

    override fun onResume() {
        super.onResume()
        globe.onResume()
    }

    override fun onPause() {
        globe.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        globe.destroy()
        super.onDestroy()
    }

    companion object {
        private const val AUTO_MS = 5 * 60_000L
    }
}
