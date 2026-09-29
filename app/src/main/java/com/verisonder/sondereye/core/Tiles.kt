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
    /** Shipped inside the app: [url] is an asset path, read without the network. */
    val bundled: Boolean = false,
    /** Drawn only on the night side, brightness as opacity (city lights). */
    val night: Boolean = false,
    /**
     * An empty (fully transparent) tile means the source has no data this deep, not an
     * empty place: treated like a 404, so the level above is stretched over it.
     */
    val emptyIsMissing: Boolean = false,
    /** Not drawn on tiles shallower than this (another source covers those zooms). */
    val minZoom: Int = 0,
    /** Not drawn on tiles deeper than this (the app draws these zooms itself). */
    val maxDrawZoom: Int = 99,
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
        /** The deepest zoom at which Esri still draws road lines. */
        const val ROAD_LINES_MAX_Z = 15

        private fun esri(service: String): (Int, Int, Int) -> String = { z, x, y ->
            // Two hostnames for the same service: twice the parallel downloads.
            val host = if ((x + y) % 2 == 0) "server" else "services"
            // blankTile=false: where Esri has no imagery it answers 404 instead of a grey
            // "Map data not yet available" picture, so the globe keeps the sharpest real tile.
            "https://$host.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/$z/$y/$x?blankTile=false"
        }

        /**
         * NASA Blue Marble Next Generation, zoom 2 to 6, inside the APK (fetched by CI,
         * tools/fetch_bluemarble.py). Public domain. Drawn wherever it is sharper than
         * what the chosen map has loaded so far, so the globe is never blank or blurry
         * at country scale, even offline.
         */
        val BLUE_MARBLE = TileSource("bluemarble", 6, false, "Base: NASA Blue Marble", bundled = true) { z, x, y ->
            "bluemarble/$z/$x/$y.jpg"
        }

        val SATELLITE = TileSource("esri-imagery", 20, false, "Imagery: Esri, Maxar, Earthstar Geographics", template = esri("World_Imagery"))
        val STREETS = TileSource("esri-streets", 19, false, "Map: Esri, HERE, Garmin, OpenStreetMap", template = esri("World_Street_Map"))

        /** Yesterday's whole Earth from NASA's VIIRS, clouds and all. Zoom 9 at most. */
        val TODAY = TileSource("gibs-viirs", 9, false, "Imagery: NASA GIBS, VIIRS NOAA-20") { z, x, y ->
            val host = "gibs-" + "abc"[(x + y) % 3]
            "https://$host.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/default/GoogleMapsCompatible_Level9/$z/$y/$x.jpg"
        }

        /** Night lights (NASA VIIRS 2012), shown on the dark side only. */
        val LIGHTS = TileSource("gibs-lights", 8, true, "Night lights: NASA", night = true) { z, x, y ->
            "https://gibs-" + "abc"[(x + y) % 3] + ".earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_CityLights_2012/default/GoogleMapsCompatible_Level8/$z/$y/$x.jpg"
        }

        /**
         * Esri's roads layer stops drawing the road lines past zoom 15 and keeps only their
         * names ("at the largest scales, the line symbols are hidden"). So it is drawn up to
         * zoom 15; deeper, the app draws the roads itself from OpenStreetMap ([OsmRoads])
         * and the names come from the same Esri layer at full depth.
         */
        val ROADS = TileSource(
            "esri-roads", ROAD_LINES_MAX_Z, true, "Roads: Esri", emptyIsMissing = true,
            maxDrawZoom = ROAD_LINES_MAX_Z, template = esri("Reference/World_Transportation"),
        )
        val ROAD_NAMES = TileSource("esri-road-names", 19, true, "Roads: Esri", minZoom = ROAD_LINES_MAX_Z + 1, template = esri("Reference/World_Transportation"))
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

    /**
     * [host, past frames oldest first]. Each frame is "unixSeconds|path": the time comes
     * from the frame's own "time" field (paths are opaque and need not contain it).
     */
    fun frames(text: String): Pair<String, List<String>> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val host = root["host"] as? String ?: throw Json.ParseError("No host")
        val past = (root["radar"] as? Map<*, *>)?.get("past") as? List<*> ?: throw Json.ParseError("No radar frames")
        val frames = past.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val path = m["path"] as? String ?: return@mapNotNull null
            "${(m["time"] as? Double)?.toLong() ?: 0}|$path"
        }
        if (frames.isEmpty()) throw Json.ParseError("No radar frames")
        return host to frames
    }

    fun frameTimeMs(frame: String): Long? = frame.substringBefore('|').toLongOrNull()?.takeIf { it > 0 }?.times(1000)
    fun framePath(frame: String): String = frame.substringAfter('|')

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
