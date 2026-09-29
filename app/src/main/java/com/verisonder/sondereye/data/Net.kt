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
        /**
         * [message] is shown on screen as-is. [code] is the HTTP status (0 when there was
         * none); [retryAfterS] is the server's own "try again in" when it sent one.
         */
        class Failed(val message: String, val code: Int = 0, val retryAfterS: Int? = null) : Outcome<Nothing>()
    }

    private const val TIMEOUT_MS = 15_000

    /** A form POST (Overpass wants its query in the body). */
    fun <T> post(url: String, body: String, what: String, source: String, parse: (String) -> T): Outcome<T> =
        request(url, what, source, body, emptyMap(), parse)

    /** A JSON POST (Gemini). */
    fun <T> postJson(url: String, json: String, what: String, source: String, parse: (String) -> T): Outcome<T> =
        request(url, what, source, json, mapOf("Content-Type" to "application/json"), parse)

    /** [what] names the source in messages ("Earthquakes", "Flights"…). */
    fun <T> get(url: String, what: String, source: String, parse: (String) -> T): Outcome<T> =
        request(url, what, source, null, emptyMap(), parse)

    /** GET with extra request headers (an API key). */
    fun <T> getWith(url: String, headers: Map<String, String>, what: String, source: String, parse: (String) -> T): Outcome<T> =
        request(url, what, source, null, headers, parse)

    /** Whether this request sent a personal key (so a refusal is about the key). */
    private fun hasKey(url: String, headers: Map<String, String>) =
        headers.keys.any { it.contains("key", true) } || Regex("[?&](key|api_?key|apikey)=").containsMatchIn(url) || "/api/area/" in url

    private fun <T> request(
        url: String, what: String, source: String, body: String?, headers: Map<String, String>, parse: (String) -> T,
    ): Outcome<T> {
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
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.instanceFollowRedirects = true
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                if (headers.keys.none { it.equals("Content-Type", true) }) {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 429) {
                val wait = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                return Outcome.Failed("$what: $source asked us to slow down (HTTP 429)", 429, wait)
            }
            if ((code == 401 || code == 403) && hasKey(url, headers)) {
                return Outcome.Failed("$what: $source refused the key (HTTP $code). Check it in the menu")
            }
            if (code == 403) return Outcome.Failed("$what: $source blocked the request (HTTP 403). It may block apps; turn it off in Today, Customise")
            if (code != 200) {
                val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    ?.let { Regex("\"message\"\\s*:\\s*\"([^\"]{1,160})").find(it)?.groupValues?.get(1) }
                return Outcome.Failed("$what: $source answered HTTP $code" + (detail?.let { " ($it)" } ?: ""))
            }
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
