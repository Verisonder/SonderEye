package com.verisonder.sondereye.data

import android.content.Context
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Period

enum class SatGroup(val slug: String, val label: String) {
    STATIONS("stations", "Space stations"),
    VISUAL("visual", "Brightest"),
    WEATHER("weather", "Weather"),
    SCIENCE("science", "Science"),
    GPS("gps-ops", "GPS"),
    GEO("geo", "Geostationary"),
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
    /** Night side shaded, with city lights. Visual, so on. */
    val dayNight: Boolean = true,
    val lights: Boolean = true,
    /** Where each aircraft has been in the last 30 minutes. */
    val trails: Boolean = true,
    /** Surveillance cameras mapped in OpenStreetMap. */
    val cameras: Boolean = false,
    val ships: Boolean = false,
    val webcams: Boolean = false,
    val fires: Boolean = false,
    /** Bus lines and stops mapped in OpenStreetMap. */
    val busLines: Boolean = false,
    /** Live bus positions, where the operator publishes them (through Transitland). */
    val buses: Boolean = false,
    /** Places the news reports fighting in (GDELT). */
    val conflicts: Boolean = false,
    /** The start-up sequence when the app opens (asked for, so on by default). */
    val bootAnimation: Boolean = true,
    /** Layers kept off the map (by their list's name) though still loaded: "Hide" in a list. */
    val hidden: Set<String> = emptySet(),
    /** No longer used: the credits Esri, OSM and RainViewer require are always shown. Kept so old settings still read. */
    val credits: Boolean = true,
    /** White chart-paper panels instead of dark ones. */
    val lightPanels: Boolean = false,
    /** North stays up (long-press the N key). */
    val northLock: Boolean = false,
    /** Loop the radar over the past hour (six frames of imagery: heavier). */
    val radarLoop: Boolean = false,
    /** How big the pins on the globe are, times their normal size (menu: Pin size). */
    val pinScale: Float = 1f,
)

/** Personal keys for the sources that need one. Kept on the phone only. */
data class Keys(val ais: String = "", val windy: String = "", val firms: String = "", val gemini: String = "", val transitland: String = "")

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
            dayNight = prefs.getBoolean("map.dayNight", d.dayNight),
            lights = prefs.getBoolean("map.lights", d.lights),
            trails = prefs.getBoolean("flights.trails", d.trails),
            cameras = prefs.getBoolean("cameras.enabled", d.cameras),
            ships = prefs.getBoolean("ships.enabled", d.ships),
            webcams = prefs.getBoolean("webcams.enabled", d.webcams),
            fires = prefs.getBoolean("fires.enabled", d.fires),
            busLines = prefs.getBoolean("busLines.enabled", d.busLines),
            buses = prefs.getBoolean("buses.enabled", d.buses),
            conflicts = prefs.getBoolean("conflicts.enabled", d.conflicts),
            bootAnimation = prefs.getBoolean("boot.enabled", d.bootAnimation),
            hidden = (prefs.getString("layers.hidden", "") ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            credits = prefs.getBoolean("map.credits", d.credits),
            lightPanels = prefs.getBoolean("ui.lightPanels", d.lightPanels),
            northLock = prefs.getBoolean("ui.northLock", d.northLock),
            radarLoop = prefs.getBoolean("weather.radarLoop", d.radarLoop),
            pinScale = (prefs.getString("ui.pinScale", null)?.toFloatOrNull() ?: d.pinScale),
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
            .putBoolean("map.dayNight", s.dayNight)
            .putBoolean("map.lights", s.lights)
            .putBoolean("flights.trails", s.trails)
            .putBoolean("cameras.enabled", s.cameras)
            .putBoolean("ships.enabled", s.ships)
            .putBoolean("webcams.enabled", s.webcams)
            .putBoolean("fires.enabled", s.fires)
            .putBoolean("busLines.enabled", s.busLines)
            .putBoolean("buses.enabled", s.buses)
            .putBoolean("conflicts.enabled", s.conflicts)
            .putBoolean("boot.enabled", s.bootAnimation)
            .putString("layers.hidden", s.hidden.joinToString(","))
            .putBoolean("map.credits", s.credits)
            .putBoolean("ui.lightPanels", s.lightPanels)
            .putBoolean("ui.northLock", s.northLock)
            .putBoolean("weather.radarLoop", s.radarLoop)
            .putString("ui.pinScale", s.pinScale.toString())
            .apply()
    }

    fun brief(): com.verisonder.sondereye.core.BriefPrefs {
        val d = com.verisonder.sondereye.core.BriefPrefs()
        return com.verisonder.sondereye.core.BriefPrefs(
            sources = prefs.getStringSet("brief.sources", null)?.toSet() ?: d.sources,
            custom = prefs.getString("brief.custom", "")!!.lines().filter { it.isNotBlank() },
            stories = prefs.getInt("brief.stories", d.stories),
            include = prefs.getString("brief.include", d.include) ?: "",
            exclude = prefs.getString("brief.exclude", d.exclude) ?: "",
            weather = prefs.getBoolean("brief.weather", d.weather),
            length = prefs.getInt("brief.length", d.length),
            language = prefs.getString("brief.language", d.language) ?: d.language,
            bullets = prefs.getBoolean("brief.bullets", d.bullets),
            focus = prefs.getString("brief.focus", d.focus) ?: "",
        )
    }

    fun saveBrief(b: com.verisonder.sondereye.core.BriefPrefs) {
        prefs.edit()
            .putStringSet("brief.sources", b.sources)
            .putString("brief.custom", b.custom.joinToString("\n"))
            .putInt("brief.stories", b.stories)
            .putString("brief.include", b.include)
            .putString("brief.exclude", b.exclude)
            .putBoolean("brief.weather", b.weather)
            .putInt("brief.length", b.length)
            .putString("brief.language", b.language)
            .putBoolean("brief.bullets", b.bullets)
            .putString("brief.focus", b.focus)
            .apply()
    }

    fun keys() = Keys(
        prefs.getString("key.ais", "") ?: "",
        prefs.getString("key.windy", "") ?: "",
        prefs.getString("key.firms", "") ?: "",
        prefs.getString("key.gemini", "") ?: "",
        prefs.getString("key.transitland", "") ?: "",
    )

    fun saveKeys(k: Keys) {
        prefs.edit().putString("key.ais", k.ais.trim()).putString("key.windy", k.windy.trim()).putString("key.firms", k.firms.trim()).putString("key.gemini", k.gemini.trim())
            .putString("key.transitland", k.transitland.trim()).apply()
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
