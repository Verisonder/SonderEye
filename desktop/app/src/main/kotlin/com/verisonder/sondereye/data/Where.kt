package com.verisonder.sondereye.data

import com.verisonder.sondereye.core.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A position, with the names Android's Location uses, so the app's code reads the same.
 * [accuracy] in metres; [time] in ms since 1970.
 */
class Location(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val time: Long = System.currentTimeMillis(),
    /** Where it came from: "set by you" or "from your internet connection (<town>)". */
    val source: String = "",
) {
    val speed: Float get() = 0f
    fun hasSpeed() = false
    fun hasAccuracy() = true
}

/**
 * Where this PC is. A PC has no GPS: the place you set in the app wins; without one, an
 * approximate position from the internet connection (the town the connection comes out in,
 * a few km off, sometimes more with a VPN).
 */
class Where(private val onFix: (Location) -> Unit, private val onProblem: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = Settings()
    private var running = false

    fun start() {
        if (running) return
        running = true
        refresh()
    }

    fun stop() {
        running = false
    }

    /** Asks again now: the place you set, or the connection's position. */
    fun refresh() {
        val mine = store.myPlace()
        if (mine != null) {
            onFix(Location(mine[0], mine[1], 25f, source = "set by you"))
            return
        }
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                Net.get(URL, "Location", "ipwho.is") { text -> parse(text) }
            }
            when (out) {
                is Net.Outcome.Ok -> onFix(out.value)
                is Net.Outcome.Failed -> onProblem(out.message + ". Set your place: long-press, or right-click, the map")
            }
        }
    }

    /** Your own place, remembered; null goes back to the connection's position. */
    fun setMine(lat: Double?, lon: Double?) {
        store.saveMyPlace(lat, lon)
        refresh()
    }

    companion object {
        /** Free, no key, HTTPS: the approximate place of the connection. */
        const val URL = "https://ipwho.is/?fields=success,message,latitude,longitude,city,country"

        fun parse(text: String): Location {
            val m = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
            if (m["success"] == false) throw Json.ParseError((m["message"] as? String) ?: "no position")
            val lat = m["latitude"] as? Double ?: throw Json.ParseError("No latitude")
            val lon = m["longitude"] as? Double ?: throw Json.ParseError("No longitude")
            val town = listOfNotNull(m["city"] as? String, m["country"] as? String).joinToString(", ")
            return Location(lat, lon, 5_000f, source = "from your internet connection" + if (town.isEmpty()) "" else " ($town)")
        }
    }
}
