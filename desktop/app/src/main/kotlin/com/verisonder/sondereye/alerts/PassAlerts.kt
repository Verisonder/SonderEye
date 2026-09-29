package com.verisonder.sondereye.alerts

import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.data.Feeds
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.ui.AppDirs
import com.verisonder.sondereye.ui.MainActivity
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.text.DateFormat
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * A Windows notification about 10 minutes before each ISS pass you can see (sunlit station,
 * dark sky, above 10°), while SonderEye is open. Uses the last position the app saw.
 */
object PassAlerts {
    private const val LEAD_MS = 10 * 60_000L
    private var timer: Timer? = null
    private var tray: TrayIcon? = null

    fun ensureChannel(@Suppress("UNUSED_PARAMETER") context: Any?) {
        if (tray != null || !SystemTray.isSupported()) return
        runCatching {
            val img = Toolkit.getDefaultToolkit().getImage(PassAlerts::class.java.getResource("/icon.png"))
            tray = TrayIcon(img, "SonderEye").apply { isImageAutoSize = true }
            SystemTray.getSystemTray().add(tray)
        }
    }

    /** Recomputes and sets the next alert, off the UI thread. [done] gets a problem or null. */
    fun schedule(@Suppress("UNUSED_PARAMETER") context: Any?, done: (String?) -> Unit = {}) {
        thread(name = "pass-alerts", isDaemon = true) {
            done(runCatching { scheduleNow() }.getOrElse { "Pass alerts: ${it.message}" })
        }
    }

    fun cancel(@Suppress("UNUSED_PARAMETER") context: Any?) {
        timer?.cancel()
        timer = null
    }

    private fun scheduleNow(): String? {
        val settings = Settings()
        cancel(null)
        if (!settings.load().passAlerts) return null
        val home = settings.myPlace() ?: settings.home() ?: return "Pass alerts: need your place once (the pin key)"
        val (result, problem) = Feeds.satellites(AppDirs.cache, "stations")
        val iss = result?.tles?.firstOrNull { it.norad == MainActivity.ISS }
            ?: return problem ?: "Pass alerts: no ISS orbit in the CelesTrak data"
        val now = System.currentTimeMillis()
        val next = Sky.passes(Sgp4(iss), home[0], home[1], now + 60_000, hours = 72.0)
            .firstOrNull { it.visible && it.startMs - LEAD_MS > now }
        val t = Timer("pass-alert", true)
        timer = t
        if (next == null) {
            // Nothing visible for 3 days: look again tomorrow with fresher orbit data.
            t.schedule(task { schedule(null) }, 24 * 3_600_000L)
            return null
        }
        val text = "Look ${Sky.compass(next.startAzDeg)}, up to ${next.maxElevationDeg.roundToInt()}°, " +
            "${((next.endMs - next.startMs) / 60_000.0).roundToInt()} min, towards ${Sky.compass(next.endAzDeg)}."
        t.schedule(task {
            notify(next.startMs, text)
            schedule(null)
        }, (next.startMs - LEAD_MS - now).coerceAtLeast(1_000))
        return problem
    }

    private fun task(block: () -> Unit) = object : TimerTask() {
        override fun run() = block()
    }

    private fun notify(startMs: Long, text: String) {
        ensureChannel(null)
        tray?.displayMessage(
            "ISS over you at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startMs)),
            text, TrayIcon.MessageType.INFO,
        )
    }
}
