package com.flappedear.betterdriver.timing.circuits

import com.flappedear.betterdriver.timing.geo.GeoPoint
import java.io.File
import java.io.IOException

/**
 * The built-in circuit directory (FBBD-79): the list bundled with the app, or a newer one fetched
 * from GitHub and kept in [cacheDir]. Works offline from the bundled copy.
 *
 * [fetch] downloads a URL as text (at most [MaxCircuitListChars]); the app passes one that runs off
 * the main thread and never during a recording.
 */
class CircuitDirectory(
    private val cacheDir: File?,
    private val bundled: () -> String = ::readBundledCircuits,
) {
    @Volatile
    var list: CircuitList = CircuitList.Empty
        private set

    /** Reads the bundled list and a cached newer one, keeping whichever has the higher revision. */
    fun load(): CircuitList {
        var best = runCatching { CircuitList.decode(bundled()) }.getOrDefault(CircuitList.Empty)
        val cached = cacheFile()?.takeIf { it.isFile }?.let { f ->
            try {
                CircuitList.decode(f.readText())
            } catch (_: CircuitListException) {
                null
            } catch (_: IOException) {
                null
            }
        }
        if (cached != null && cached.revision > best.revision) best = cached
        list = best
        return best
    }

    fun find(at: GeoPoint): Circuit? = list.find(at)

    // Any failure of the injected download is reported, never thrown; each outcome returns early.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun refresh(fetch: (String) -> String): CircuitRefresh {
        val text: String
        val fetched: CircuitList
        try {
            text = fetch(CircuitListUrl)
            fetched = CircuitList.decode(text)
        } catch (_: Exception) {
            return CircuitRefresh.Failed
        }
        if (fetched.revision <= list.revision) return CircuitRefresh.UpToDate
        list = fetched
        val file = cacheFile() ?: return CircuitRefresh.NotSaved
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(file)) CircuitRefresh.Updated else CircuitRefresh.NotSaved
        } catch (_: IOException) {
            CircuitRefresh.NotSaved
        }
    }

    private fun cacheFile(): File? = cacheDir?.let { File(it, "circuits.json") }
}

/** The list shipped inside the app. */
fun readBundledCircuits(): String =
    CircuitDirectory::class.java.getResourceAsStream(BundledCircuitResource)?.use { it.readBytes().decodeToString() }
        ?: throw IOException("bundled circuit list missing")

