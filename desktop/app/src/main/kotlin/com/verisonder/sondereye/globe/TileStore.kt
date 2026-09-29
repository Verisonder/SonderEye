package com.verisonder.sondereye.globe

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import java.io.File
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
import javax.swing.SwingUtilities

/**
 * Map tiles: downloaded on a pool of 8, newest request first (where you are now loads
 * before what you flew past), kept on disk, decoded off the UI thread and handed to it.
 * The images and the "nothing there" set are touched on the UI thread only.
 */
class TileStore(
    private val cacheDir: File,
    /** A tile arrived or turned out absent: the globe should draw again. */
    private val onChange: () -> Unit,
) {
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, Lifo())
    private val bundledPool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, Lifo())
    private val inFlight = ConcurrentHashMap.newKeySet<SourcedTile>()
    private val lastWanted = ConcurrentHashMap<SourcedTile, Long>()
    private val failedAt = ConcurrentHashMap<SourcedTile, Long>()
    private val frame = AtomicLong(0)

    /** Decoded tiles, most recently used last. UI thread. */
    private val images = LinkedHashMap<SourcedTile, Image>(512, 0.75f, true)
    /** Tiles the source has nothing for (404, or empty where that means "none"). UI thread. */
    val absent = HashSet<SourcedTile>()

    @Volatile var failures = 0
        private set
    @Volatile var lastFailure: String? = null
        private set

    fun get(t: SourcedTile): Image? = images[t]

    /**
     * The tile's shader, made once and kept with it. Made afresh every frame they piled up in
     * native memory (Java's collector never saw them as large, so it did not hurry): gigabytes.
     */
    fun shader(t: SourcedTile): Shader? {
        val img = images[t] ?: return null
        return shaders.getOrPut(t) { img.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null) }
    }

    private val shaders = HashMap<SourcedTile, Shader>()
    fun has(t: SourcedTile) = images.containsKey(t)
    fun nextFrame() = frame.incrementAndGet()

    fun failedRecently(t: SourcedTile): Boolean {
        val at = failedAt[t] ?: return false
        return System.currentTimeMillis() - at < RETRY_MS
    }

    fun request(t: SourcedTile) {
        lastWanted[t] = frame.get()
        if (t in inFlight || t in absent) return
        val failed = failedAt[t]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        if (!inFlight.add(t)) return
        (if (t.source.bundled) bundledPool else pool).execute { run(t) }
    }

    private fun run(t: SourcedTile) {
        try {
            if (frame.get() - (lastWanted[t] ?: 0L) > STALE_FRAMES) return
            val bytes = if (t.source.bundled) bundled(t) else disk(t).takeIf { it.exists() }?.readBytes() ?: download(t)
            if (bytes == null) return ui { absent.add(t); onChange() }
            val img = decode(bytes)
            if (img == null) {
                disk(t).delete()
                if (t.source.emptyIsMissing) return ui { absent.add(t); onChange() }
                throw Failure("not an image (${bytes.size} bytes)")
            }
            if (t.source.emptyIsMissing && img.second) return ui { absent.add(t); onChange() }
            failedAt.remove(t)
            ui {
                images[t] = img.first
                failures = 0
                onChange()
            }
        } catch (e: Failure) {
            failedAt[t] = System.currentTimeMillis()
            failures++
            lastFailure = "${t.source.credit.substringBefore(':')}: ${e.message}"
            ui { onChange() }
        } catch (e: Throwable) {
            failedAt[t] = System.currentTimeMillis()
            failures++
            lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            ui { onChange() }
        } finally {
            inFlight.remove(t)
        }
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    /** The decoded image, and whether every pixel is transparent. Null: not an image. */
    private fun decode(bytes: ByteArray): Pair<Image, Boolean>? {
        val encoded = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        // Decoded here, on the worker, not on the UI thread at first draw.
        val bmp = runCatching { Bitmap.makeFromImage(encoded) }.getOrNull()
        encoded.close() // the compressed copy is not needed once decoded
        if (bmp == null) return null
        val px = bmp.readPixels()
        var empty = px != null
        if (px != null) {
            var i = 3
            while (i < px.size) {
                if (px[i] != 0.toByte()) { empty = false; break }
                i += 4
            }
        }
        bmp.setImmutable()
        val img = Image.makeFromBitmap(bmp) // shares the pixels, which outlive the Bitmap wrapper
        bmp.close()
        return img to empty
    }

    private fun bundled(t: SourcedTile): ByteArray? =
        TileStore::class.java.getResourceAsStream("/" + t.source.url(t.key))?.use { it.readBytes() }

    private fun disk(t: SourcedTile) = File(cacheDir, "tiles/${t.source.id}/${t.key.z}/${t.key.x}/${t.key.y}")

    private class Failure(msg: String) : Exception(msg)

    private fun download(t: SourcedTile): ByteArray? {
        val conn = URL(t.source.url(t.key)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SonderEye (github.com/Verisonder/SonderEye)")
            val code = conn.responseCode
            if (code == 404 || code == 204) return null
            // Past its data, a reference layer may refuse rather than say "none": the same thing.
            if (t.source.emptyIsMissing && code in 400..499) return null
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

    /** Keeps at most [CAP] images, never one drawn this frame. UI thread. */
    fun trim(used: Set<SourcedTile>) {
        if (images.size <= CAP) return
        val it = images.entries.iterator()
        while (images.size > CAP && it.hasNext()) {
            val e = it.next()
            if (e.key in used || e.key.key.z <= 3) continue
            e.value.close()
            shaders.remove(e.key)?.close()
            it.remove()
        }
    }

    /** Bytes kept on disk for tiles. */
    fun diskBytes(): Long = File(cacheDir, "tiles").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearDisk() {
        File(cacheDir, "tiles").deleteRecursively()
    }

    /** Keeps the disk cache under [DISK_CAP] by deleting the oldest files. */
    fun trimDisk() {
        val files = File(cacheDir, "tiles").walkTopDown().filter { it.isFile }.toMutableList()
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
        bundledPool.shutdownNow()
    }

    companion object {
        private const val RETRY_MS = 20_000L
        private const val STALE_FRAMES = 240L
        /** A PC has the memory: more tiles kept decoded than on the phone. */
        private const val CAP = 600
        private const val DISK_CAP = 1024L * 1024 * 1024
    }
}
