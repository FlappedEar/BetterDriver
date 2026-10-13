package com.flappedear.betterdriver.analysis.coaching

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The coaching history (FBBD-180): one `<track id>/<session id>.json` in [dir] per analysed session
 * ([SessionCoachingRecord]). It lives with the coach's files, so a backup carries it and a restore
 * brings it back (FBBD-156). It outlives its session: deleting a session's recording (to free space)
 * keeps what was learned from it, as it holds no positions. It goes with its track. Writes are
 * synced and renamed into place; a file that cannot be read, or that is not what its place says,
 * counts as none.
 */
class CoachingHistoryStore(private val dir: File) {
    // Defaults written too, so every file says its format and version.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun get(trackId: String, sessionId: String): SessionCoachingRecord? =
        read(fileFor(trackId, sessionId))?.takeIf { it.trackId == trackId && it.sessionId == sessionId }

    /** Every session's record at [trackId], oldest first. */
    fun history(trackId: String): List<SessionCoachingRecord> =
        File(dir, checkedId(trackId)).listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
            .mapNotNull { f -> read(f)?.takeIf { it.trackId == trackId && f.name == it.sessionId + Suffix } }
            .sortedWith(SessionOrder)

    /**
     * Saves [record] unless the same is saved already; returns whether it wrote. A session analysed
     * again at another track leaves the old one, as its focus record does (FBBD-20).
     */
    fun save(record: SessionCoachingRecord): Boolean {
        val f = fileFor(record.trackId, record.sessionId)
        val elsewhere = dir.listFiles { d -> d.isDirectory && d.name != record.trackId }.orEmpty()
            .map { File(it, f.name) }.filter { it.exists() }
        elsewhere.forEach { it.delete() }
        if (read(f) == record) return elsewhere.isNotEmpty()
        writeAtomically(f, json.encodeToString(SessionCoachingRecord.serializer(), record))
        return true
    }

    /** Deletes every record of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        val d = File(dir, checkedId(trackId))
        d.deleteRecursively()
        return !d.exists()
    }

    private fun read(f: File): SessionCoachingRecord? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(SessionCoachingRecord.serializer(), f.readText())
                .takeIf { it.format == SessionCoachingRecord.Format && it.version == SessionCoachingRecord.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(trackId: String, sessionId: String) =
        File(File(dir, checkedId(trackId)), checkedId(sessionId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}

/** Sessions in the order they were driven; the id breaks a tie. */
internal val SessionOrder: Comparator<SessionCoachingRecord> =
    compareBy({ it.sessionStartUtcMillis }, { it.sessionId })

/** A track or session [id], once it is known to be safe as a file name. */
private fun checkedId(id: String): String {
    require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_." } && id != "." && id != "..") {
        "bad id: $id"
    }
    return id
}

/** Writes [text] to a temporary file, syncs it and renames it over [target], so a reader never sees half of it. */
private fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
