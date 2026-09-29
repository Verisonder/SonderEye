package com.verisonder.sondereye.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper

/**
 * The phone's position from the platform LocationManager: GPS and network providers,
 * no Google Play services. Runs only between [start] and [stop] (while the app is on
 * screen and location is switched on in the app).
 */
class Where(private val context: Context, private val onFix: (Location) -> Unit, private val onProblem: (String) -> Unit) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val listener = LocationListener { loc -> onFix(loc) }

    /** Caller has checked the permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onProblem("Location: switched off in the phone's settings")
            return
        }
        running = true
        // Best of the last known fixes first, so the dot appears at once.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let(onFix)
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, 5_000L, 10f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                onProblem("Location: permission was withdrawn")
            } catch (e: IllegalArgumentException) {
                // Provider missing on this phone; the other one still works.
            }
        }
    }

    /**
     * Asks every provider for a new fix now, instead of waiting for the next update (which
     * only comes after moving 10 m). Starts the updates too, if they were not running.
     */
    @SuppressLint("MissingPermission")
    fun refresh() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onProblem("Location: switched off in the phone's settings")
            return
        }
        for (p in providers) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, null, context.mainExecutor) { loc -> if (loc != null) onFix(loc) }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                onProblem("Location: permission was withdrawn")
            } catch (e: IllegalArgumentException) {
                // Provider missing on this phone; the other one still answers.
            }
        }
        start()
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
    }
}
