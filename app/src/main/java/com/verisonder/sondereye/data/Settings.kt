package com.verisonder.sondereye.data

import android.content.Context
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Period

data class QuakeSettings(
    val enabled: Boolean = true,
    val minMag: MinMag = MinMag.M25,
    val period: Period = Period.DAY,
    val autoRefresh: Boolean = false,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): QuakeSettings {
        val d = QuakeSettings()
        return QuakeSettings(
            enabled = prefs.getBoolean("quakes.enabled", d.enabled),
            minMag = enumOr(prefs.getString("quakes.minMag", null), d.minMag),
            period = enumOr(prefs.getString("quakes.period", null), d.period),
            autoRefresh = prefs.getBoolean("quakes.autoRefresh", d.autoRefresh),
        )
    }

    fun save(s: QuakeSettings) {
        prefs.edit()
            .putBoolean("quakes.enabled", s.enabled)
            .putString("quakes.minMag", s.minMag.name)
            .putString("quakes.period", s.period.name)
            .putBoolean("quakes.autoRefresh", s.autoRefresh)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
