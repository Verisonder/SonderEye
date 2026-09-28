package com.verisonder.sondereye.data

import android.content.Context
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Period

enum class SatGroup(val slug: String, val label: String) {
    STATIONS("stations", "Space stations"),
    VISUAL("visual", "Brightest"),
    WEATHER("weather", "Weather"),
    SCIENCE("science", "Science"),
}

/** Every layer's settings. Earthquakes start on (the first layer); every other layer starts off. */
data class Layers(
    val quakes: Boolean = true,
    val minMag: MinMag = MinMag.M25,
    val period: Period = Period.DAY,
    val autoRefresh: Boolean = false,
    val flights: Boolean = false,
    val satellites: Boolean = false,
    val satGroup: SatGroup = SatGroup.STATIONS,
    val events: Boolean = false,
    /** Show where the phone is. Off until the user asks for it. */
    val location: Boolean = false,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Layers {
        val d = Layers()
        return Layers(
            quakes = prefs.getBoolean("quakes.enabled", d.quakes),
            minMag = enumOr(prefs.getString("quakes.minMag", null), d.minMag),
            period = enumOr(prefs.getString("quakes.period", null), d.period),
            autoRefresh = prefs.getBoolean("quakes.autoRefresh", d.autoRefresh),
            flights = prefs.getBoolean("flights.enabled", d.flights),
            satellites = prefs.getBoolean("sats.enabled", d.satellites),
            satGroup = enumOr(prefs.getString("sats.group", null), d.satGroup),
            events = prefs.getBoolean("events.enabled", d.events),
            location = prefs.getBoolean("location.enabled", d.location),
        )
    }

    fun save(s: Layers) {
        prefs.edit()
            .putBoolean("quakes.enabled", s.quakes)
            .putString("quakes.minMag", s.minMag.name)
            .putString("quakes.period", s.period.name)
            .putBoolean("quakes.autoRefresh", s.autoRefresh)
            .putBoolean("flights.enabled", s.flights)
            .putBoolean("sats.enabled", s.satellites)
            .putString("sats.group", s.satGroup.name)
            .putBoolean("events.enabled", s.events)
            .putBoolean("location.enabled", s.location)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
