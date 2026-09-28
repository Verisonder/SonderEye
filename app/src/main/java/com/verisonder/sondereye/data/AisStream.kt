package com.verisonder.sondereye.data

import android.os.Handler
import android.os.Looper
import com.verisonder.sondereye.core.Ais
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Live ships from AISStream over a WebSocket, for one box at a time. Messages are parsed
 * on OkHttp's thread and handed to the main thread. Every problem, including a refused
 * key, arrives through [onProblem] as a sentence for the screen.
 */
class AisStream(
    private val onMessage: (Ais.Msg) -> Unit,
    private val onProblem: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var generation = 0

    /** Opens (or re-opens) the stream for this box. */
    fun open(key: String, s: Double, w: Double, n: Double, e: Double) {
        close()
        val gen = ++generation
        val sub = Ais.subscription(key, s, w, n, e)
        socket = client.newWebSocket(Request.Builder().url(Ais.URL).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sub) // must be within 3 s of connecting
                main.post { if (gen == generation) onProblem(null) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { Ais.parse(text, System.currentTimeMillis()) }.getOrNull() ?: return
                if (m is Ais.Msg.Other) return
                main.post {
                    if (gen != generation) return@post
                    if (m is Ais.Msg.Error) onProblem("Ships: AISStream says \"${m.text}\". Check the key in the menu") else onMessage(m)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation && code != 1000) onProblem("Ships: stream closed ($code ${reason.ifEmpty { "no reason" }})") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                main.post { if (gen == generation) onProblem("Ships: stream failed ($why). Retrying") }
            }
        })
    }

    fun close() {
        generation++
        socket?.close(1000, null)
        socket = null
    }
}
