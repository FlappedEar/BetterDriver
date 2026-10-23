package com.flappedear.betterdriver.feature.track.editor

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.flappedear.betterdriver.timing.map.TileSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Decoded tiles kept in memory, in bytes: 160 tiles of 256 px, or 40 of 512 px. */
private const val MemoryBytes = 40 * 1024 * 1024
private const val BytesPerPixel = 4
private const val Threads = 6
private const val TimeoutMs = 15_000
private const val RetryAfterMs = 30_000L
private const val MaxDownloadBytes = 4 * 1024 * 1024
private const val HttpOk = 200

/** Tiles on disk are trimmed back to this, oldest first (checked once per loader). */
private const val DiskLimitBytes = 300L * 1024 * 1024

/**
 * Map tiles for the track editor (UI-26). A tile is downloaded the first time it is shown and kept
 * in [cacheDir], so a track viewed once at home can be edited at the track without signal. Only
 * tiles that are viewed are fetched, as the tile providers' policies ask.
 * [userAgent] identifies the app, as OpenStreetMap's tile policy requires.
 *
 * [get] is called while drawing; [loaded] goes up when a tile arrives, so maps that read it redraw.
 *
 * [keyFile] keeps the MapTiler key Arek enters on the map (FBBD-230): on the phone only, never in
 * the repository or the APK.
 */
@Suppress("TooManyFunctions") // Memory, disk and network for the editor's map and the perfect lap's (FBBD-316).
class TileLoader(private val cacheDir: File, private val userAgent: String, private val keyFile: File? = null) {

    private data class Key(val source: String, val z: Int, val x: Int, val y: Int)

    private val memory = object : LruCache<Key, ImageBitmap>(MemoryBytes) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * BytesPerPixel
    }
    private val pending = ConcurrentHashMap.newKeySet<Key>()
    private val failedAt = HashMap<Key, Long>()

    /** Goes up with each key change, so downloads made with the old key do not count as failures. Main thread. */
    private var keyGeneration = 0
    private val io = Executors.newFixedThreadPool(Threads) { r -> Thread(r, "map-tiles").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    var loaded by mutableIntStateOf(0)
        private set

    /** Goes up when a tile cannot be loaded, for a "no connection" note on the map. */
    var failures by mutableIntStateOf(0)
        private set

    /** The MapTiler key, or null until one is entered. Main thread. */
    var mapTilerKey by mutableStateOf<String?>(null)
        private set

    init {
        io.execute {
            val key = readKey(keyFile)
            if (key != null) main.post { mapTilerKey = key }
            // GUGiK orthophoto tiles from before FBBD-230, no longer shown.
            File(cacheDir, "gugik-orto").deleteRecursively()
            trimDisk()
        }
    }

    /** Keeps [key] for MapTiler's satellite map; a blank key removes it. Main thread. */
    fun updateMapTilerKey(key: String) {
        val value = key.trim().takeIf { it.isNotEmpty() }
        mapTilerKey = value
        // Tiles that failed or are loading with the old key are fetched again at once with the new one.
        keyGeneration++
        failedAt.clear()
        pending.clear()
        val f = keyFile ?: return
        io.execute {
            try {
                if (value == null) {
                    f.delete()
                } else {
                    f.parentFile?.mkdirs()
                    f.writeText(value)
                }
            } catch (_: IOException) {
                // Kept for this run only; entered again after a restart.
            }
        }
    }

    /** The tile if it is in memory; otherwise null, and it is loaded from disk or the network. Main thread. */
    fun get(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = Key(source.id, z, x, y)
        memory.get(key)?.let { return it }
        request(source, key)
        return null
    }

    /**
     * The tile from memory, the disk or the network, waiting for it; null when it cannot be had.
     * Not on the main thread: the perfect lap's video (FBBD-316) draws each frame with its tiles.
     */
    fun load(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = Key(source.id, z, x, y)
        return memory.get(key) ?: (readCached(key) ?: download(source, key))?.also { memory.put(key, it) }
    }

    /** The tile only if it is already in memory; nothing is loaded. For drawing a coarser tile meanwhile. */
    fun peek(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? = memory.get(Key(source.id, z, x, y))

    private fun request(source: TileSource, key: Key) {
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RetryAfterMs) return
        if (!pending.add(key)) return
        val generation = keyGeneration
        io.execute {
            val image = readCached(key) ?: download(source, key)
            main.post {
                // After a key change the new request owns the entry: this one only adds a tile it got.
                val current = generation == keyGeneration
                if (current) pending.remove(key)
                if (image != null) {
                    memory.put(key, image)
                    failedAt.remove(key)
                    loaded++
                } else if (current) {
                    failedAt[key] = SystemClock.elapsedRealtime()
                    failures++
                }
            }
        }
    }

    private fun file(key: Key) = File(cacheDir, "${key.source}/${key.z}/${key.x}/${key.y}.tile")

    private fun readCached(key: Key): ImageBitmap? {
        val f = file(key)
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }

    // A failed download leaves nothing on disk and is retried later; it is never an error.
    @Suppress("ReturnCount")
    private fun download(source: TileSource, key: Key): ImageBitmap? {
        val bytes = try {
            fetch(source.url(key.z, key.x, key.y))
        } catch (_: IOException) {
            null
        } ?: return null
        // A WMS reports errors as XML with status 200: only real images are kept.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val f = file(key)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (_: IOException) {
            // Shown anyway; only the offline copy is missing.
        }
        return bitmap.asImageBitmap()
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TimeoutMs
        conn.readTimeout = TimeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        return try {
            if (conn.responseCode != HttpOk) {
                null
            } else {
                conn.inputStream.use { input ->
                    val bytes = input.readNBytesCompat(MaxDownloadBytes + 1)
                    bytes.takeIf { it.size <= MaxDownloadBytes }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun trimDisk() {
        val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= DiskLimitBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DiskLimitBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun close() {
        io.shutdownNow()
    }
}

/** The key kept in [file], or null when there is none. */
private fun readKey(file: File?): String? = try {
    file?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: IOException) {
    null
}

/** Reads up to [limit] bytes; InputStream.readNBytes needs API 33. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
