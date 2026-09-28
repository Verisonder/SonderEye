package com.verisonder.sondereye.globe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.verisonder.sondereye.core.TileKey
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads imagery tiles on a small pool. The queue is last-in-first-out: when the user
 * moves, the tiles for where they are now load before the ones they flew past, and a
 * tile nobody has asked for in a while is dropped without downloading.
 * HTTP caching (HttpResponseCache, installed by the app) makes revisits free.
 */
class TileLoader(
    private val onLoaded: (TileKey, Bitmap) -> Unit,
    private val onFailed: (TileKey, String) -> Unit,
) {
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, Lifo())
    private val inFlight = ConcurrentHashMap.newKeySet<TileKey>()
    private val lastWanted = ConcurrentHashMap<TileKey, Long>()
    private val failedAt = ConcurrentHashMap<TileKey, Long>()
    private val frame = AtomicLong(0)

    /** Called once per rendered frame, before [request]. */
    fun nextFrame() {
        frame.incrementAndGet()
    }

    fun request(k: TileKey) {
        lastWanted[k] = frame.get()
        if (k in inFlight) return
        val failed = failedAt[k]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        if (!inFlight.add(k)) return
        pool.execute { run(k) }
    }

    private fun run(k: TileKey) {
        try {
            // Wanted within the last ~2 seconds of frames? Otherwise it scrolled away.
            val stale = frame.get() - (lastWanted[k] ?: 0L) > STALE_FRAMES
            if (stale) return
            val bmp = fetch(k)
            failedAt.remove(k)
            onLoaded(k, bmp)
        } catch (e: Failure) {
            failedAt[k] = System.currentTimeMillis()
            onFailed(k, e.message ?: "unknown error")
        } finally {
            inFlight.remove(k)
        }
    }

    private class Failure(msg: String) : Exception(msg)

    private fun fetch(k: TileKey): Bitmap {
        val conn = try {
            URL(k.url()).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure("could not open (${e.message})")
        }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.useCaches = true
            conn.setRequestProperty("User-Agent", "SonderEye (github.com/Verisonder/SonderEye)")
            val code = conn.responseCode
            if (code != 200) throw Failure("HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw Failure("not an image (${bytes.size} bytes)")
        } catch (e: UnknownHostException) {
            throw Failure("no connection")
        } catch (e: SocketTimeoutException) {
            throw Failure("timed out")
        } catch (e: IOException) {
            throw Failure("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    companion object {
        private const val RETRY_MS = 20_000L
        private const val STALE_FRAMES = 120L
    }
}
