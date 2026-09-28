package com.verisonder.sondereye.data

import com.verisonder.sondereye.core.Json
import com.verisonder.sondereye.core.MinMag
import com.verisonder.sondereye.core.Period
import com.verisonder.sondereye.core.Usgs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Fetches a USGS summary feed. Blocking: call from Dispatchers.IO. */
object UsgsClient {

    sealed class Outcome {
        class Ok(val result: Usgs.Result) : Outcome()
        /** [message] is shown on screen as-is. */
        class Failed(val message: String) : Outcome()
    }

    private const val TIMEOUT_MS = 15_000

    fun fetch(min: MinMag, period: Period): Outcome {
        val conn = try {
            URL(Usgs.feedUrl(min, period)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Outcome.Failed("Earthquakes: could not open the feed (${e.message})")
        }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/geo+json, application/json")
            conn.setRequestProperty("User-Agent", "SonderEye (github.com/Verisonder/SonderEye)")
            val code = conn.responseCode
            if (code != 200) return Outcome.Failed("Earthquakes: USGS answered HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Outcome.Ok(Usgs.parse(text))
        } catch (e: UnknownHostException) {
            Outcome.Failed("Earthquakes: no connection (USGS not reachable)")
        } catch (e: SocketTimeoutException) {
            Outcome.Failed("Earthquakes: USGS did not answer within ${TIMEOUT_MS / 1000} s")
        } catch (e: Json.ParseError) {
            Outcome.Failed("Earthquakes: the feed could not be read (${e.message})")
        } catch (e: IOException) {
            Outcome.Failed("Earthquakes: download failed (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            conn.disconnect()
        }
    }
}
