package com.verisonder.sondereye.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** One GET, with every failure turned into a short sentence for the screen. Blocking. */
object Net {
    sealed class Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>()
        /** [message] is shown on screen as-is. */
        class Failed(val message: String) : Outcome<Nothing>()
    }

    private const val TIMEOUT_MS = 15_000

    /** [what] names the source in messages ("Earthquakes", "Flights"…). */
    fun <T> get(url: String, what: String, source: String, parse: (String) -> T): Outcome<T> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Outcome.Failed("$what: could not open the feed (${e.message})")
        }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "SonderEye (github.com/Verisonder/SonderEye)")
            val code = conn.responseCode
            if (code == 429) return Outcome.Failed("$what: $source asked us to slow down (HTTP 429)")
            if (code != 200) return Outcome.Failed("$what: $source answered HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            try {
                Outcome.Ok(parse(text))
            } catch (e: Exception) {
                Outcome.Failed("$what: the $source data could not be read (${e.message})")
            }
        } catch (e: UnknownHostException) {
            Outcome.Failed("$what: no connection ($source not reachable)")
        } catch (e: SocketTimeoutException) {
            Outcome.Failed("$what: $source did not answer within ${TIMEOUT_MS / 1000} s")
        } catch (e: IOException) {
            Outcome.Failed("$what: download failed (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            conn.disconnect()
        }
    }
}
