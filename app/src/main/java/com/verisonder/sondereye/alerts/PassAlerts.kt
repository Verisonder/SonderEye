package com.verisonder.sondereye.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.verisonder.sondereye.R
import com.verisonder.sondereye.core.Sgp4
import com.verisonder.sondereye.core.Sky
import com.verisonder.sondereye.data.Feeds
import com.verisonder.sondereye.data.Settings
import com.verisonder.sondereye.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * A notification about 10 minutes before each ISS pass you can see (sunlit station,
 * dark sky, above 10°). One alarm at a time: when it fires it notifies and sets the
 * next one. Uses the last position the app saw; nothing runs in between.
 */
object PassAlerts {
    const val CHANNEL = "passes"
    private const val ACTION_FIRE = "com.verisonder.sondereye.PASS_ALERT"
    private const val ACTION_RECHECK = "com.verisonder.sondereye.PASS_RECHECK"
    private const val LEAD_MS = 10 * 60_000L

    /** Recomputes and sets the next alarm, off the main thread. [done] gets a problem or null. */
    fun schedule(context: Context, done: (String?) -> Unit = {}) {
        val app = context.applicationContext
        thread(name = "pass-alerts") { done(runCatching { scheduleNow(app) }.getOrElse { "Pass alerts: ${it.message}" }) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, ACTION_FIRE, null))
        am.cancel(pending(context, ACTION_RECHECK, null))
    }

    private fun scheduleNow(context: Context): String? {
        val settings = Settings(context)
        cancel(context)
        if (!settings.load().passAlerts) return null
        val home = settings.home() ?: return "Pass alerts: need your location once (tap the pin)"
        val (result, problem) = Feeds.satellites(context.cacheDir, "stations")
        val iss = result?.tles?.firstOrNull { it.norad == MainActivity.ISS }
            ?: return problem ?: "Pass alerts: no ISS orbit in the CelesTrak data"
        val now = System.currentTimeMillis()
        val next = Sky.passes(Sgp4(iss), home[0], home[1], now + 60_000, hours = 72.0)
            .firstOrNull { it.visible && it.startMs - LEAD_MS > now }
        val am = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            // Nothing visible for 3 days: look again tomorrow with fresher orbit data.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 24 * 3_600_000L, pending(context, ACTION_RECHECK, null))
            return null
        }
        val text = "Look ${Sky.compass(next.startAzDeg)}, up to ${next.maxElevationDeg.roundToInt()}°, " +
            "${((next.endMs - next.startMs) / 60_000.0).roundToInt()} min, towards ${Sky.compass(next.endAzDeg)}."
        val intent = Intent().putExtra("start", next.startMs).putExtra("text", text)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.startMs - LEAD_MS, pending(context, ACTION_FIRE, intent))
        return problem
    }

    private fun pending(context: Context, action: String, extras: Intent?): PendingIntent {
        val i = Intent(context, PassReceiver::class.java).setAction(action)
        extras?.extras?.let(i::putExtras)
        return PendingIntent.getBroadcast(context, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "ISS passes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A heads-up about 10 minutes before the ISS crosses your sky"
            })
        }
    }

    internal fun notify(context: Context, startMs: Long, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("ISS over you at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startMs)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(startMs.toInt(), n)
    }

    const val FIRE = ACTION_FIRE
}

/** Fires the alert and sets the next one; also re-arms after a reboot or an update. */
class PassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        if (intent.action == PassAlerts.FIRE) {
            PassAlerts.notify(context, intent.getLongExtra("start", System.currentTimeMillis()), intent.getStringExtra("text") ?: "")
        }
        PassAlerts.schedule(context) { pending.finish() }
    }
}
