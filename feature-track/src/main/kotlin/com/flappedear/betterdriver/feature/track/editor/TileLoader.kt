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
import com.flappedear.betterdriver.timing.map.GoogleMapTiles
import com.flappedear.betterdriver.timing.map.GoogleSession
import com.flappedear.betterdriver.timing.map.GoogleTileBudget
import com.flappedear.betterdriver.timing.map.GoogleTileDay
import com.flappedear.betterdriver.timing.map.TileSource
import com.flappedear.betterdriver.timing.map.TileSources
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Decoded tiles kept in memory, in bytes: 256 tiles of 256 px, or 64 of 512 px, so the perfect
 * lap in 3D (up to 48 tiles in one picture, FBBD-316) never drops a tile it is still showing.
 */
private const val MemoryBytes = 64 * 1024 * 1024

/** Google's tiles kept compressed in memory: some 600 of 512 px, more than a track's 3D lap uses. */
private const val GoogleBytes = 32 * 1024 * 1024
private const val BytesPerPixel = 4
private const val Threads = 6
private const val TimeoutMs = 15_000
private const val RetryAfterMs = 30_000L

/** A tile the video could not have, or every download after the network failed, is not tried again for this long. */
private const val LoadRetryAfterMs = 10 * 60_000L
private const val MaxDownloadBytes = 4 * 1024 * 1024
private const val HttpOk = 200

/** A Google session is made again this long before it expires, or this long after one could not be made. */
private const val SessionMarginMs = 60 * 60_000L
private const val SessionRetryMs = 30_000L

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
 * [keyFile] keeps the MapTiler key Arek enters on the map (FBBD-230), [googleKeyFile] his Google
 * Map Tiles API key (FBBD-412): on the phone only, never in the repository or the APK. [headers]
 * go with every request to Google: the app's package and signing certificate, which a Google key
 * limited to Android apps checks. Google's tiles are never written to disk (its terms); the others are.
 */
@Suppress("TooManyFunctions") // Memory, disk and network for the editor's map and the perfect lap's (FBBD-316).
class TileLoader(
    private val cacheDir: File,
    private val userAgent: String,
    private val keyFile: File? = null,
    private val googleKeyFile: File? = null,
    val headers: Map<String, String> = emptyMap(),
) {

    private data class Key(val source: String, val z: Int, val x: Int, val y: Int)

    private val memory = object : LruCache<Key, ImageBitmap>(MemoryBytes) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * BytesPerPixel
    }
    /**
     * Google's tiles as fetched (JPEG, about 50 KB each), in memory only, as its terms ask: a whole
     * track's tiles fit, so watching more laps of it costs no more Google tiles until the app is
     * closed. The decoded [memory] holds only 64 of them, fewer than one lap of the 3D picture uses.
     */
    private val googleBytes = object : LruCache<Key, ByteArray>(GoogleBytes) {
        override fun sizeOf(key: Key, value: ByteArray) = value.size
    }
    private val pending = ConcurrentHashMap.newKeySet<Key>()
    private val failedAt = HashMap<Key, Long>()

    /** For [load], off the main thread: tiles it could not have, and until when the network is skipped. */
    private val loadFailedAt = ConcurrentHashMap<Key, Long>()

    @Volatile private var offlineUntil = 0L

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

    /** The Google key, or null until one is entered. Main thread. */
    var googleKey by mutableStateOf<String?>(null)
        private set

    /** Google's satellite map, once a session is made with [googleKey]; null before. Main thread. */
    var google by mutableStateOf<TileSource?>(null)
        private set

    /** "Google Maps" and the owners of the Google photos fetched so far. Main thread. */
    var googleCredit by mutableStateOf(GoogleMapTiles.credit(emptyList()))
        private set

    /** The Google session and the key it was made with; read by io threads for the credit. */
    @Volatile private var session: Pair<String, GoogleSession>? = null
    private var sessionPending = false
    private var sessionTriedAt = 0L

    /** Google tiles fetched today, against the daily cap (GoogleTileBudget); guarded by itself. */
    private val budgetLock = Any()
    private var budget: GoogleTileDay? = null
    private val budgetFile = googleKeyFile?.resolveSibling("google-tiles-today.txt")

    /** Google's copyright text by viewport square ("z/x/y"); "" while it is asked for. io threads. */
    private val copyrights = ConcurrentHashMap<String, String>()

    init {
        io.execute {
            val key = readKey(keyFile)
            if (key != null) main.post { mapTilerKey = key }
            synchronized(budgetLock) { budget = GoogleTileBudget.read(readText(budgetFile)) }
            val google = readKey(googleKeyFile)
            if (google != null) {
                main.post {
                    googleKey = google
                    ensureGoogle()
                }
            }
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

    /** Keeps [key] for Google's satellite map; a blank key removes it. Main thread. */
    fun updateGoogleKey(key: String) {
        val value = key.trim().takeIf { it.isNotEmpty() }
        googleKey = value
        google = null
        session = null
        sessionTriedAt = 0L
        // Photos and their owners' credit go together: the tiles are fetched again, and credited.
        copyrights.clear()
        googleCredit = GoogleMapTiles.credit(emptyList())
        memory.snapshot().keys.filter { it.source == GoogleId }.forEach { memory.remove(it) }
        googleBytes.evictAll()
        keyGeneration++
        failedAt.clear()
        pending.clear()
        ensureGoogle()
        val f = googleKeyFile ?: return
        io.execute {
            try {
                if (value == null) f.delete() else f.apply { parentFile?.mkdirs() }.writeText(value)
            } catch (_: IOException) {
                // Kept for this run only; entered again after a restart.
            }
        }
    }

    /**
     * Makes a Google session if there is a key and none is at hand or it expires within the hour;
     * after a failure, not again for 30 s. Nothing while today's Google tiles are used up, and the
     * map comes back the next day. Called often (the maps call it every 30 s). Main thread.
     */
    fun ensureGoogle() {
        val key = googleKey ?: return
        val spent = googleSpentToday()
        val now = SystemClock.elapsedRealtime()
        val s = session?.second
        val fresh = s != null && s.expirySeconds * MillisPerSecond - System.currentTimeMillis() > SessionMarginMs
        // A new day after the cap was used: the session made before still serves.
        val restore = fresh && google == null
        if (restore && !spent && s != null) google = TileSources.googleSatellite(key, s.token)
        val busy = sessionPending || (sessionTriedAt != 0L && now - sessionTriedAt < SessionRetryMs)
        if (spent || fresh || busy) return
        sessionPending = true
        sessionTriedAt = now
        makeSession(key)
    }

    /** Asks Google for a session with [key] on an io thread; Google's map follows once it is made. */
    private fun makeSession(key: String) {
        val generation = keyGeneration
        io.execute {
            val made = try {
                post(GoogleMapTiles.createSessionUrl(key), GoogleMapTiles.SESSION_REQUEST)
                    ?.let { GoogleMapTiles.parseSession(it, System.currentTimeMillis() / MillisPerSecond) }
            } catch (e: IOException) {
                GoogleMapTiles.logSessionFailed("no connection (${e.javaClass.simpleName})")
                null
            }
            if (made != null) GoogleMapTiles.logSessionMade(made.expirySeconds)
            main.post {
                sessionPending = false
                if (generation != keyGeneration || made == null) return@post
                session = key to made
                google = TileSources.googleSatellite(key, made.token)
            }
        }
    }

    /** True when today's Google tiles are used up (GoogleTileBudget). */
    private fun googleSpentToday(): Boolean = synchronized(budgetLock) {
        GoogleTileBudget.spent(budget, GoogleTileBudget.today(System.currentTimeMillis()))
    }

    /**
     * Counts one Google tile against today's cap and keeps the count; false when the cap is used,
     * and then Google's map is dropped until [ensureGoogle] finds a new day. io thread.
     */
    private fun takeGoogleTile(): Boolean {
        val next = synchronized(budgetLock) {
            GoogleTileBudget.take(budget, GoogleTileBudget.today(System.currentTimeMillis()))?.also {
                budget = it
                // Kept under the lock and replaced whole, so an older count never overwrites a newer one.
                keepBudget(it)
            }
        }
        if (next == null) main.post { google = null }
        return next != null
    }

    private fun keepBudget(day: GoogleTileDay) {
        val f = budgetFile ?: return
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(GoogleTileBudget.write(day))
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (_: IOException) {
            // Counted for this run only.
        }
    }

    /** What the map must say while it shows [source]: for Google, also whose photos they are. Main thread. */
    fun credit(source: TileSource): String =
        if (source.id == GoogleId) googleCredit else source.attribution

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
        return memory.get(key) ?: (readCached(source, key) ?: loadDownload(source, key))?.also { memory.put(key, it) }
    }

    /**
     * The video draws every frame with the same tiles: a tile that failed, or a dropped network,
     * costs one timeout, not one per frame.
     */
    private fun loadDownload(source: TileSource, key: Key): ImageBitmap? {
        val now = SystemClock.elapsedRealtime()
        val failed = loadFailedAt[key]
        val skip = now < offlineUntil || (failed != null && now - failed < LoadRetryAfterMs)
        val image = if (skip) null else download(source, key)
        if (image == null && !skip) loadFailedAt[key] = SystemClock.elapsedRealtime()
        return image
    }

    /** The tile only if it is already in memory; nothing is loaded. For drawing a coarser tile meanwhile. */
    fun peek(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? = memory.get(Key(source.id, z, x, y))

    private fun request(source: TileSource, key: Key) {
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RetryAfterMs) return
        if (!pending.add(key)) return
        val generation = keyGeneration
        io.execute {
            val image = readCached(source, key) ?: download(source, key)
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

    private fun readCached(source: TileSource, key: Key): ImageBitmap? {
        val bitmap = if (source.stored) {
            file(key).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        } else {
            googleBytes.get(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        return bitmap?.asImageBitmap()
    }

    // A failed download leaves nothing on disk and is retried later; it is never an error.
    @Suppress("ReturnCount")
    private fun download(source: TileSource, key: Key): ImageBitmap? {
        if (source.id == GoogleId && !takeGoogleTile()) return null
        val bytes = try {
            fetch(source.url(key.z, key.x, key.y))
        } catch (_: IOException) {
            offlineUntil = SystemClock.elapsedRealtime() + LoadRetryAfterMs
            null
        } ?: return null
        // A WMS reports errors as XML with status 200: only real images are kept.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (source.id == GoogleId) noteCopyright(key)
        if (!source.stored) {
            googleBytes.put(key, bytes)
            return bitmap.asImageBitmap()
        }
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

    /**
     * Asks Google once per viewport square whose photos those are, for the credit; again after a
     * failure. io thread.
     */
    private fun noteCopyright(key: Key) {
        val area = "${key.z}/${GoogleMapTiles.viewportArea(key.x)}/${GoogleMapTiles.viewportArea(key.y)}"
        if (copyrights.putIfAbsent(area, "") != null) return
        val text = session?.let { (apiKey, made) ->
            try {
                fetch(GoogleMapTiles.viewportUrl(apiKey, made.token, key.z, key.x, key.y))
                    ?.decodeToString()?.let(GoogleMapTiles::parseCopyright)
            } catch (_: IOException) {
                null
            }
        }
        if (text == null) {
            copyrights.remove(area)
        } else {
            copyrights[area] = text
            val credit = GoogleMapTiles.credit(copyrights.values)
            main.post { googleCredit = credit }
        }
    }

    /** POSTs [body] as JSON to [url]; the answer, or null unless it is 200. io thread. */
    private fun post(url: String, body: String): String? {
        val conn = open(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            if (conn.responseCode != HttpOk) {
                // 400 or 403: the key is wrong, or limited to another app or API.
                GoogleMapTiles.logSessionFailed("HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.use { it.readNBytesCompat(MaxDownloadBytes) }.decodeToString()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TimeoutMs
        conn.readTimeout = TimeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        // Only Google checks them; the other tile servers are not told the app's certificate.
        if (url.startsWith(GoogleMapTiles.HOST)) {
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        }
        return conn
    }

    private fun fetch(url: String): ByteArray? {
        val conn = open(url)
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

private const val GoogleId = "google-satellite"
private const val MillisPerSecond = 1000L

private fun readText(file: File?): String? = try {
    file?.takeIf { it.isFile }?.readText()
} catch (_: IOException) {
    null
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
