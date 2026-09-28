package com.verisonder.sondereye.globe

import android.graphics.Bitmap
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.verisonder.sondereye.core.TileKey
import com.verisonder.sondereye.core.TileSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A tile of one source. */
data class SourcedTile(val source: TileSource, val key: TileKey) {
    val id get() = source.id + "/" + key.z + "/" + key.x + "/" + key.y
    override fun equals(other: Any?) = other is SourcedTile && other.source.id == source.id && other.key == key
    override fun hashCode() = source.id.hashCode() * 31 + key.hashCode()
}

/**
 * Downloads tiles on a pool of 8. Last-in-first-out: when the user moves, the tiles for
 * where they are now load before the ones they flew past, and a tile nobody has asked
 * for in a while is dropped without downloading. Every tile is kept on disk, so a place
 * seen once loads instantly the next time.
 */
class TileLoader(
    private val cacheDir: File,
    private val assets: AssetManager,
    private val onLoaded: (SourcedTile, Bitmap) -> Unit,
    /** The source has nothing there (404): not an error, the globe keeps the parent. */
    private val onAbsent: (SourcedTile) -> Unit,
    private val onFailed: (SourcedTile, String) -> Unit,
) {
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, Lifo())
    /** Bundled tiles come from the APK in milliseconds: never queued behind downloads. */
    private val assetPool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, Lifo())
    private val inFlight = ConcurrentHashMap.newKeySet<SourcedTile>()
    private val lastWanted = ConcurrentHashMap<SourcedTile, Long>()
    private val failedAt = ConcurrentHashMap<SourcedTile, Long>()
    private val frame = AtomicLong(0)

    /**
     * Decoded tiles waiting for the GL thread. Each is 128–256 KB of native memory, and
     * the GL thread uploads only while it draws, so without a limit they pile up (flying
     * around, radar loop, app in the background) until the process runs out of memory.
     * A worker waits here instead of decoding more; [uploaded] frees a place.
     */
    private val waiting = Semaphore(MAX_WAITING)

    /** The GL thread took (or dropped) one decoded tile. */
    fun uploaded() = waiting.release()

    init {
        pool.execute { trimDisk() }
    }

    /** Called once per rendered frame, before [request]. */
    fun nextFrame() {
        frame.incrementAndGet()
    }

    fun request(t: SourcedTile) {
        lastWanted[t] = frame.get()
        if (t in inFlight) return
        val failed = failedAt[t]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        if (!inFlight.add(t)) return
        (if (t.source.bundled) assetPool else pool).execute { run(t) }
    }

    private fun run(t: SourcedTile) {
        var holding = false
        try {
            if (frame.get() - (lastWanted[t] ?: 0L) > STALE_FRAMES) return
            waiting.acquire()
            holding = true
            // Waited: maybe the view has moved on meanwhile.
            if (frame.get() - (lastWanted[t] ?: 0L) > STALE_FRAMES) return
            val bytes = if (t.source.bundled) {
                runCatching { assets.open(t.source.url(t.key)).use { it.readBytes() } }.getOrNull() ?: return onAbsent(t)
            } else {
                disk(t).takeIf { it.exists() }?.let { f -> runCatching { f.readBytes() }.getOrNull() }
                    ?: download(t) ?: return onAbsent(t)
            }
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = if (t.source.transparent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) {
                disk(t).delete()
                throw Failure("not an image (${bytes.size} bytes)")
            }
            failedAt.remove(t)
            holding = false // the place now belongs to the queued bitmap until uploaded()
            onLoaded(t, bmp)
        } catch (e: Failure) {
            failedAt[t] = System.currentTimeMillis()
            onFailed(t, e.message ?: "unknown error")
        } catch (e: InterruptedException) {
            // Shutting down.
        } catch (e: OutOfMemoryError) {
            // Never let a worker die on this: the pool would try to start a new thread,
            // which is exactly what fails when memory is short.
            failedAt[t] = System.currentTimeMillis()
            onFailed(t, "phone low on memory")
        } catch (e: RuntimeException) {
            failedAt[t] = System.currentTimeMillis()
            onFailed(t, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (holding) waiting.release()
            inFlight.remove(t)
            if (lastWanted.size > 20_000) prune()
        }
    }

    /** Bookkeeping maps otherwise grow with every tile ever seen. */
    private fun prune() {
        val now = frame.get()
        lastWanted.entries.removeIf { now - it.value > STALE_FRAMES }
        val cut = System.currentTimeMillis() - RETRY_MS
        failedAt.entries.removeIf { it.value < cut }
    }

    private class Failure(msg: String) : Exception(msg)

    private fun disk(t: SourcedTile) = File(cacheDir, "tiles/" + t.id)

    /** The tile's bytes, or null when the source has nothing there. Saved to disk. */
    private fun download(t: SourcedTile): ByteArray? {
        val conn = try {
            URL(t.source.url(t.key)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure("could not open (${e.message})")
        }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            // Tiles have their own disk cache below; keeping them in the HTTP cache too would store each twice.
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "SonderEye (github.com/Verisonder/SonderEye)")
            val code = conn.responseCode
            if (code == 404 || code == 204) return null
            if (code != 200) throw Failure("HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            runCatching {
                val f = disk(t)
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".part")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            }
            return bytes
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

    /** Keeps the tile cache under [DISK_CAP] by deleting the least recently written files. */
    private fun trimDisk() {
        val root = File(cacheDir, "tiles")
        val files = root.walkTopDown().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= DISK_CAP * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    fun shutdown() {
        pool.shutdownNow()
        assetPool.shutdownNow()
    }

    companion object {
        private const val RETRY_MS = 20_000L
        private const val STALE_FRAMES = 120L
        private const val DISK_CAP = 500L * 1024 * 1024
        /** At most this many decoded tiles in memory waiting for the GL thread (~8 MB). */
        private const val MAX_WAITING = 32
    }
}
