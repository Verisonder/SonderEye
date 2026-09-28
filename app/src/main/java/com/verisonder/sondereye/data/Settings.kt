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

enum class MapStyle(val label: String) {
    SATELLITE("Satellite"),
    STREETS("Streets"),
    TODAY("Today from space"),
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
    val map: MapStyle = MapStyle.SATELLITE,
    /** Roads and place names over satellite imagery. On: asked for. */
    val roads: Boolean = true,
    val labels: Boolean = true,
    val radar: Boolean = false,
    /** Notification before visible ISS passes. */
    val passAlerts: Boolean = false,
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
            map = enumOr(prefs.getString("map.style", null), d.map),
            roads = prefs.getBoolean("map.roads", d.roads),
            labels = prefs.getBoolean("map.labels", d.labels),
            radar = prefs.getBoolean("weather.radar", d.radar),
            passAlerts = prefs.getBoolean("sats.passAlerts", d.passAlerts),
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
            .putString("map.style", s.map.name)
            .putBoolean("map.roads", s.roads)
            .putBoolean("map.labels", s.labels)
            .putBoolean("weather.radar", s.radar)
            .putBoolean("sats.passAlerts", s.passAlerts)
            .apply()
    }

    /** Last known position, for pass alerts computed while the app is closed. */
    fun saveHome(lat: Double, lon: Double) {
        prefs.edit().putString("home.lat", lat.toString()).putString("home.lon", lon.toString()).apply()
    }

    fun home(): DoubleArray? {
        val lat = prefs.getString("home.lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("home.lon", null)?.toDoubleOrNull() ?: return null
        return doubleArrayOf(lat, lon)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
