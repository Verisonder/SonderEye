package com.verisonder.sondereye.core

import java.net.URLEncoder
import java.util.Locale

// ---- Bus lines from OpenStreetMap ---------------------------------------------------------

/** One direction of a bus line: an OpenStreetMap route relation. */
class BusLine(
    val id: Long,
    val ref: String?,
    val name: String?,
    val from: String?,
    val to: String?,
    val network: String?,
    val operator: String?,
    /** 0xRRGGBB from the route's colour tag, when it has one. */
    val colour: Int?,
    /** The route in runs of [lat, lon]; a run breaks where the map data or the box stops. */
    val paths: List<List<DoubleArray>>,
    /** Stops in order (platforms when mapped, else stop positions). */
    val stopIds: List<Long>,
) {
    /**
     * 0xRRGGBB to draw it in: its own colour from OpenStreetMap, or else one of a set of
     * clearly different colours picked by its number, so neighbouring lines stand apart
     * (and both directions of a line share one).
     */
    val shown: Int get() = colour ?: LINE_COLOURS[Math.floorMod((ref ?: name ?: id.toString()).hashCode(), LINE_COLOURS.size)]

    /**
     * The operator to show. In Tangier the buses are Isal's now, while the map data still says
     * ALSA (Isal took over the same network), so there the name is updated.
     */
    val operatorShown: String?
        get() {
            val n = network ?: operator ?: return null
            val p = paths.firstOrNull()?.firstOrNull() ?: return n
            val tangier = p[0] in 35.60..35.92 && p[1] in -6.05..-5.55
            return if (!tangier) n else Regex("(?i)alsa").replace(n) { if (it.value == it.value.uppercase()) "ISAL" else "Isal" }
        }

    /** "L1" or the name: what a person calls it. */
    val short: String get() = ref ?: name ?: "Bus line"

    /** "Beni Makada → Boukhalef", or the name when the ends are not mapped. */
    val route: String get() = if (from != null && to != null) "$from → $to" else name ?: ""
}

/** Twelve colours that stay apart from each other and from the satellite picture. */
private val LINE_COLOURS = intArrayOf(
    0xFF5A5A, 0x4FC3F7, 0xFFD54F, 0xBA68C8, 0x81C784, 0xFF8A65,
    0x4DD0E1, 0xF06292, 0xAED581, 0x9575CD, 0xFFB74D, 0x64B5F6,
)

class BusStop(val id: Long, val lat: Double, val lon: Double, val name: String?, val lineIds: List<Long>)

class BusNetwork(val lines: List<BusLine>, val stops: List<BusStop>)

object BusLines {
    /**
     * Every bus and trolleybus route crossing the box, clipped to it, and their stops with
     * names. Query body for Overpass (POST data=…).
     */
    fun query(s: Double, w: Double, n: Double, e: Double): String {
        val box = "%.5f,%.5f,%.5f,%.5f".format(Locale.ROOT, s, w, n, e)
        return "data=" + URLEncoder.encode(
            "[out:json][timeout:40];" +
                "relation[\"type\"=\"route\"][\"route\"~\"^(bus|trolleybus)$\"]($box)->.r;" +
                ".r out geom($box);" +
                "node(r.r)($box);out;",
            "UTF-8",
        )
    }

    fun parse(text: String): BusNetwork {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["remark"] as? String)?.let { if ("runtime error" in it) throw Json.ParseError(it.take(120)) }
        val els = root["elements"] as? List<*> ?: throw Json.ParseError("No elements")
        val names = HashMap<Long, String?>()
        val points = HashMap<Long, DoubleArray>()
        val lines = ArrayList<BusLine>()
        for (e in els) {
            val m = e as? Map<*, *> ?: continue
            val id = (m["id"] as? Double)?.toLong() ?: continue
            val tags = m["tags"] as? Map<*, *> ?: emptyMap<String, Any>()
            when (m["type"]) {
                "node" -> {
                    val lat = m["lat"] as? Double ?: continue
                    val lon = m["lon"] as? Double ?: continue
                    points[id] = doubleArrayOf(lat, lon)
                    names[id] = tags["name"] as? String
                }
                "relation" -> {
                    val paths = ArrayList<List<DoubleArray>>()
                    val platforms = ArrayList<Long>()
                    val stops = ArrayList<Long>()
                    for (mem in m["members"] as? List<*> ?: emptyList<Any>()) {
                        val mm = mem as? Map<*, *> ?: continue
                        val role = mm["role"] as? String ?: ""
                        when (mm["type"]) {
                            "way" -> {
                                // Points outside the box come back empty: they break the run.
                                var run = ArrayList<DoubleArray>()
                                for (g in mm["geometry"] as? List<*> ?: emptyList<Any>()) {
                                    val p = g as? Map<*, *>
                                    val lat = p?.get("lat") as? Double
                                    val lon = p?.get("lon") as? Double
                                    if (lat == null || lon == null) {
                                        if (run.size >= 2) paths.add(run)
                                        run = ArrayList()
                                    } else {
                                        run.add(doubleArrayOf(lat, lon))
                                    }
                                }
                                if (run.size >= 2) paths.add(run)
                            }
                            "node" -> {
                                val ref = (mm["ref"] as? Double)?.toLong() ?: continue
                                if (role.startsWith("platform")) platforms.add(ref)
                                else if (role.startsWith("stop")) stops.add(ref)
                            }
                        }
                    }
                    lines.add(
                        BusLine(
                            id = id,
                            ref = (tags["ref"] as? String)?.trim()?.ifEmpty { null },
                            name = (tags["name"] as? String)?.trim()?.ifEmpty { null },
                            from = tags["from"] as? String,
                            to = tags["to"] as? String,
                            network = tags["network"] as? String,
                            operator = tags["operator"] as? String,
                            colour = colour(tags["colour"] as? String),
                            paths = paths,
                            stopIds = (platforms.ifEmpty { stops }).distinct(),
                        ),
                    )
                }
            }
        }
        val servedBy = LinkedHashMap<Long, MutableList<Long>>()
        for (l in lines) for (s in l.stopIds) if (s in points) servedBy.getOrPut(s) { ArrayList() }.add(l.id)
        val busStops = servedBy.map { (id, ls) -> BusStop(id, points[id]!![0], points[id]!![1], names[id], ls.distinct()) }
        return BusNetwork(lines.filter { it.paths.isNotEmpty() || it.stopIds.isNotEmpty() }, busStops)
    }

    /** "#e30613" or "#f00" to 0xRRGGBB; names and anything else: none. */
    fun colour(s: String?): Int? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        val full = when {
            h.length == 6 -> h
            h.length == 3 -> h.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return full.toIntOrNull(16)
    }
}

// ---- Live buses: GTFS Realtime, found through Transitland -----------------------------------

/** A vehicle from a GTFS Realtime feed. */
class Bus(
    /** Unique within the app: feed and vehicle. */
    val key: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    val speedMs: Double?,
    val routeId: String?,
    val label: String?,
    val atMs: Long?,
    /** The feed it came from, by name. */
    val feed: String,
)

/** A GTFS Realtime feed near the view. */
class RtFeed(val onestopId: String, val name: String)

/** Where a feed's vehicle positions are, and whether its source needs its own key. */
class RtSource(val vehiclesUrl: String?, val needsKey: Boolean)

object Transitland {
    private const val BASE = "https://transit.land/api/v2/rest"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Operators whose service area is within [radiusM] of the point, with their feeds. */
    fun operatorsUrl(lat: Double, lon: Double, radiusM: Int, key: String) =
        "$BASE/operators?lat=%.5f&lon=%.5f&radius=%d&limit=20&apikey=%s".format(Locale.ROOT, lat, lon, radiusM, enc(key.trim()))

    fun feedUrl(onestopId: String, key: String) = "$BASE/feeds/${enc(onestopId)}?apikey=${enc(key.trim())}"

    /** Transitland's cached copy, as JSON (only where the feed's licence lets it share one). */
    fun vehiclesUrl(onestopId: String, key: String) =
        "$BASE/feeds/${enc(onestopId)}/download_latest_rt/vehicle_positions.json?apikey=${enc(key.trim())}"

    /** The realtime feeds among the operators found. */
    fun parseRtFeeds(text: String): List<RtFeed> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val ops = root["operators"] as? List<*> ?: throw Json.ParseError("No operators")
        val out = LinkedHashMap<String, RtFeed>()
        for (o in ops) {
            val om = o as? Map<*, *> ?: continue
            val opName = om["name"] as? String ?: om["short_name"] as? String
            for (f in om["feeds"] as? List<*> ?: emptyList<Any>()) {
                val fm = f as? Map<*, *> ?: continue
                val spec = (fm["spec"] as? String)?.lowercase()?.replace("_", "-") ?: continue
                if (spec != "gtfs-rt") continue
                val id = fm["onestop_id"] as? String ?: continue
                out[id] = RtFeed(id, (fm["name"] as? String) ?: opName ?: id)
            }
        }
        return out.values.toList()
    }

    fun parseSource(text: String): RtSource {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val feed = (root["feeds"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: root
        val urls = feed["urls"] as? Map<*, *>
        val auth = feed["authorization"] as? Map<*, *>
        val type = (auth?.get("type") as? String)?.trim().orEmpty()
        return RtSource((urls?.get("realtime_vehicle_positions") as? String)?.ifBlank { null }, type.isNotEmpty())
    }

    /** A FeedMessage as JSON: field names in camelCase or snake_case, numbers as numbers or strings. */
    fun parseVehicles(text: String, feed: RtFeed): List<Bus> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val msg = (root["feed_message"] ?: root["feedMessage"]) as? Map<*, *> ?: root
        val entities = msg["entity"] as? List<*> ?: return emptyList()
        return entities.mapNotNull { e ->
            val em = e as? Map<*, *> ?: return@mapNotNull null
            val v = em["vehicle"] as? Map<*, *> ?: return@mapNotNull null
            val pos = v["position"] as? Map<*, *> ?: return@mapNotNull null
            val lat = num(pos["latitude"]) ?: return@mapNotNull null
            val lon = num(pos["longitude"]) ?: return@mapNotNull null
            if (lat == 0.0 && lon == 0.0) return@mapNotNull null
            val trip = v["trip"] as? Map<*, *>
            val desc = v["vehicle"] as? Map<*, *>
            val vid = (desc?.get("id") as? String) ?: (em["id"] as? String) ?: "%.5f,%.5f".format(Locale.ROOT, lat, lon)
            Bus(
                key = feed.onestopId + "/" + vid,
                lat = lat, lon = lon,
                bearing = num(pos["bearing"]),
                speedMs = num(pos["speed"]),
                routeId = (trip?.get("routeId") ?: trip?.get("route_id")) as? String,
                label = desc?.get("label") as? String,
                atMs = num(v["timestamp"])?.let { (it * 1000).toLong() }?.takeIf { it > 0 },
                feed = feed.name,
            )
        }
    }

    private fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

/**
 * The few GTFS Realtime fields a map needs, read straight from the protocol buffer (for
 * feeds Transitland may not share, fetched from their source).
 */
object GtfsRt {
    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                if (p >= end) throw Json.ParseError("Truncated feed")
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
                if (shift > 63) throw Json.ParseError("Bad number in feed")
            }
        }
        fun fixed32(): Int {
            if (p + 4 > end) throw Json.ParseError("Truncated feed")
            val r = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return r
        }
        fun sub(): Reader {
            val n = varint().toInt()
            if (n < 0 || p + n > end) throw Json.ParseError("Truncated feed")
            val r = Reader(b, p, p + n)
            p += n
            return r
        }
        fun string() = sub().let { String(b, it.p, it.end - it.p, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> sub()
                5 -> p += 4
                else -> throw Json.ParseError("Not a GTFS Realtime feed")
            }
            if (p > end) throw Json.ParseError("Truncated feed")
        }
    }

    fun parseVehicles(bytes: ByteArray, feed: RtFeed): List<Bus> {
        val out = ArrayList<Bus>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 2 && tag and 7 == 2) entity(r.sub(), feed)?.let(out::add) else r.skip(tag and 7)
        }
        return out
    }

    private fun entity(r: Reader, feed: RtFeed): Bus? {
        var id: String? = null
        var bus: Bus? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            when {
                tag ushr 3 == 1 && tag and 7 == 2 -> id = r.string()
                tag ushr 3 == 4 && tag and 7 == 2 -> bus = vehicle(r.sub(), feed, id)
                else -> r.skip(tag and 7)
            }
        }
        return bus
    }

    private fun vehicle(r: Reader, feed: RtFeed, entityId: String?): Bus? {
        var lat: Double? = null
        var lon: Double? = null
        var bearing: Double? = null
        var speed: Double? = null
        var route: String? = null
        var vid: String? = null
        var label: String? = null
        var at: Long? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 2 -> { // TripDescriptor
                    val t = r.sub()
                    while (t.more()) {
                        val tt = t.varint().toInt()
                        if (tt ushr 3 == 5 && tt and 7 == 2) route = t.string() else t.skip(tt and 7)
                    }
                }
                field == 2 && wire == 2 -> { // Position
                    val q = r.sub()
                    while (q.more()) {
                        val qt = q.varint().toInt()
                        if (qt and 7 == 5) {
                            val f = java.lang.Float.intBitsToFloat(q.fixed32()).toDouble()
                            when (qt ushr 3) {
                                1 -> lat = f
                                2 -> lon = f
                                3 -> bearing = f
                                5 -> speed = f
                            }
                        } else q.skip(qt and 7)
                    }
                }
                field == 5 && wire == 0 -> at = r.varint() * 1000
                field == 8 && wire == 2 -> { // VehicleDescriptor
                    val d = r.sub()
                    while (d.more()) {
                        val dt = d.varint().toInt()
                        when {
                            dt ushr 3 == 1 && dt and 7 == 2 -> vid = d.string()
                            dt ushr 3 == 2 && dt and 7 == 2 -> label = d.string()
                            else -> d.skip(dt and 7)
                        }
                    }
                }
                else -> r.skip(wire)
            }
        }
        val la = lat ?: return null
        val lo = lon ?: return null
        if (la == 0.0 && lo == 0.0) return null
        return Bus(
            key = feed.onestopId + "/" + (vid ?: entityId ?: "%.5f,%.5f".format(Locale.ROOT, la, lo)),
            lat = la, lon = lo, bearing = bearing, speedMs = speed, routeId = route, label = label,
            atMs = at?.takeIf { it > 0 }, feed = feed.name,
        )
    }
}

/**
 * Riding a bus line: where you are along its stops. [stops] are [lat, lon] in the line's
 * order; the next stop is the one ahead of you: the nearest, or the one after it once you
 * are between the two (or standing at the nearest).
 */
object Ride {
    class Progress(val next: Int, val metres: Double, val left: Int)

    fun progress(stops: List<DoubleArray>, lat: Double, lon: Double): Progress? {
        if (stops.isEmpty()) return null
        fun d(a: DoubleArray, bLat: Double, bLon: Double) = Geo.angle(Geo.ecef(a[0], a[1]), Geo.ecef(bLat, bLon)) * EARTH_R
        val i = stops.indices.minBy { d(stops[it], lat, lon) }
        val last = stops.lastIndex
        val here = d(stops[i], lat, lon)
        val next = when {
            i < last && here < AT_STOP_M -> i + 1
            i < last && d(stops[i + 1], lat, lon) < d(stops[i], stops[i + 1][0], stops[i + 1][1]) -> i + 1
            else -> i
        }
        return Progress(next, d(stops[next], lat, lon), last - next + 1)
    }

    /** Standing this close to a stop counts as being at it. */
    const val AT_STOP_M = 35.0
}
