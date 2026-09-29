package com.verisonder.sondereye.data

import com.verisonder.sondereye.core.Ais
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import javax.swing.SwingUtilities

/**
 * Live ships from AISStream over a WebSocket (Java's own client), for one box at a time.
 * Messages are parsed on the socket's thread and handed to the UI thread. Every problem,
 * including a refused key, arrives through [onProblem] as a sentence for the screen.
 */
class AisStream(
    private val onMessage: (Ais.Msg) -> Unit,
    private val onProblem: (String?) -> Unit,
) {
    private val client = HttpClient.newHttpClient()
    private var socket: WebSocket? = null
    @Volatile private var generation = 0

    private fun main(block: () -> Unit) = SwingUtilities.invokeLater(block)

    /** Opens (or re-opens) the stream for this box. */
    fun open(key: String, s: Double, w: Double, n: Double, e: Double) {
        close()
        val gen = ++generation
        val sub = Ais.subscription(key, s, w, n, e)
        val text = StringBuilder()
        client.newWebSocketBuilder().buildAsync(URI.create(Ais.URL), object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) {
                ws.sendText(sub, true) // must be within 3 s of connecting
                main { if (gen == generation) onProblem(null) }
                ws.request(1)
            }

            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) {
                    handle(text.toString(), gen)
                    text.setLength(0)
                }
                ws.request(1)
                return null
            }

            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val b = ByteArray(data.remaining()); data.get(b)
                text.append(String(b, Charsets.UTF_8))
                if (last) {
                    handle(text.toString(), gen)
                    text.setLength(0)
                }
                ws.request(1)
                return null
            }

            override fun onClose(ws: WebSocket, code: Int, reason: String): CompletionStage<*>? {
                main { if (gen == generation && code != 1000) onProblem("Ships: stream closed ($code ${reason.ifEmpty { "no reason" }})") }
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                main { if (gen == generation) onProblem("Ships: stream failed (${error.message ?: error.javaClass.simpleName}). Retrying") }
            }
        }).whenComplete { ws, err ->
            if (ws != null) {
                if (gen == generation) socket = ws else ws.abort()
            } else if (err != null) {
                main { if (gen == generation) onProblem("Ships: stream failed (${err.cause?.message ?: err.message}). Retrying") }
            }
        }
    }

    private fun handle(text: String, gen: Int) {
        val m = runCatching { Ais.parse(text, System.currentTimeMillis()) }.getOrNull() ?: return
        if (m is Ais.Msg.Other) return
        main {
            if (gen != generation) return@main
            if (m is Ais.Msg.Error) onProblem("Ships: AISStream says \"${m.text}\". Check the key in the menu") else onMessage(m)
        }
    }

    fun close() {
        generation++
        socket?.let { runCatching { it.sendClose(WebSocket.NORMAL_CLOSURE, "") } }
        socket = null
    }
}
