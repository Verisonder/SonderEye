package com.verisonder.sondereye.core

/**
 * A tiled map layer in Web Mercator. The globe draws one base and any number of
 * transparent overlays on top, all through the same tile machinery.
 */
class TileSource(
    /** Stable id: names the disk cache and the texture cache. */
    val id: String,
    val maxZoom: Int,
    /** Overlays have transparency and are drawn over the base. */
    val transparent: Boolean,
    /** Shown under the map, as the provider asks. */
    val credit: String,
    val alpha: Float = 1f,
    private val template: (z: Int, x: Int, y: Int) -> String,
) {
    fun url(k: TileKey) = template(k.z, k.x, k.y)

    /** The key actually fetched for [k]: past [maxZoom], the ancestor at [maxZoom], stretched. */
    fun keyFor(k: TileKey): TileKey {
        var a = k
        while (a.z > maxZoom) a = a.parent()!!
        return a
    }

    companion object {
        private fun esri(service: String): (Int, Int, Int) -> String = { z, x, y ->
            // Two hostnames for the same service: twice the parallel downloads.
            val host = if ((x + y) % 2 == 0) "server" else "services"
            // blankTile=false: where Esri has no imagery it answers 404 instead of a grey
            // "Map data not yet available" picture, so the globe keeps the sharpest real tile.
            "https://$host.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/$z/$y/$x?blankTile=false"
        }

        val SATELLITE = TileSource("esri-imagery", 20, false, "Imagery: Esri, Maxar, Earthstar Geographics", template = esri("World_Imagery"))
        val STREETS = TileSource("esri-streets", 19, false, "Map: Esri, HERE, Garmin, OpenStreetMap", template = esri("World_Street_Map"))

        /** Yesterday's whole Earth from NASA's VIIRS, clouds and all. Zoom 9 at most. */
        val TODAY = TileSource("gibs-viirs", 9, false, "Imagery: NASA GIBS, VIIRS NOAA-20") { z, x, y ->
            val host = "gibs-" + "abc"[(x + y) % 3]
            "https://$host.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/default/GoogleMapsCompatible_Level9/$z/$y/$x.jpg"
        }

        val ROADS = TileSource("esri-roads", 19, true, "Roads: Esri", template = esri("Reference/World_Transportation"))
        val LABELS = TileSource("esri-labels", 19, true, "Labels: Esri", template = esri("Reference/World_Boundaries_and_Places"))

        /** RainViewer radar, past 10-minute frame. [path] comes from their weather-maps.json. */
        fun radar(host: String, path: String) = TileSource("rain-" + path.substringAfterLast('/'), 7, true, "Radar: RainViewer", alpha = 0.8f) { z, x, y ->
            "$host$path/256/$z/$x/$y/2/1_1.png"
        }
    }
}

/** RainViewer's index of radar frames. */
object RainViewer {
    const val URL = "https://api.rainviewer.com/public/weather-maps.json"

    /** [host, path] of the latest past frame. */
    fun latest(text: String): Pair<String, String> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val host = root["host"] as? String ?: throw Json.ParseError("No host")
        val past = (root["radar"] as? Map<*, *>)?.get("past") as? List<*> ?: throw Json.ParseError("No radar frames")
        val path = (past.lastOrNull() as? Map<*, *>)?.get("path") as? String ?: throw Json.ParseError("No radar frames")
        return host to path
    }
}

/** Current weather at a point, from Open-Meteo (no key). */
data class Weather(
    val tempC: Double,
    val humidity: Int?,
    val code: Int,
    val windKmh: Double?,
    val windFromDeg: Double?,
    val precipMm: Double?,
    val cloudPct: Int?,
) {
    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    val description: String get() = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Code $code"
    }
}

object OpenMeteo {
    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(java.util.Locale.ROOT, lat)}" +
            "&longitude=${"%.4f".format(java.util.Locale.ROOT, lon)}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,precipitation,cloud_cover"

    fun parse(text: String): Weather {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val c = root["current"] as? Map<*, *> ?: throw Json.ParseError(root["reason"] as? String ?: "No current weather")
        return Weather(
            tempC = c["temperature_2m"] as? Double ?: throw Json.ParseError("No temperature"),
            humidity = (c["relative_humidity_2m"] as? Double)?.toInt(),
            code = (c["weather_code"] as? Double)?.toInt() ?: -1,
            windKmh = c["wind_speed_10m"] as? Double,
            windFromDeg = c["wind_direction_10m"] as? Double,
            precipMm = c["precipitation"] as? Double,
            cloudPct = (c["cloud_cover"] as? Double)?.toInt(),
        )
    }
}
