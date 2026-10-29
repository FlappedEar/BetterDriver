package com.flappedear.betterdriver.analysis.coaching

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.filesEndingWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The coaching history (FBBD-180): one `<track id>/<session id>.json` in [dir] per analysed session
 * ([SessionCoachingRecord]). It lives with the coach's files, so a backup carries it and a restore
 * brings it back (FBBD-156). It outlives its session: deleting a session's recording (to free space)
 * keeps what was learned from it, as it holds no positions. It goes with its track. Writes are
 * synced and renamed into place; a file that cannot be read, or that is not what its place says,
 * counts as none. Shared by the Android app and the iOS app (FBBD-359).
 */
class CoachingHistoryStore(private val dir: String) {
    // Defaults written too, so every file says its format and version.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun get(trackId: String, sessionId: String): SessionCoachingRecord? =
        read(fileFor(trackId, sessionId))?.takeIf { it.trackId == trackId && it.sessionId == sessionId }

    /** Every session's record at [trackId], oldest first. */
    fun history(trackId: String): List<SessionCoachingRecord> =
        CoreFiles.join(dir, checkedId(trackId)).let { d ->
            CoreFiles.filesEndingWith(d, Suffix)
                .mapNotNull { name ->
                    read(CoreFiles.join(d, name))?.takeIf { it.trackId == trackId && name == it.sessionId + Suffix }
                }
        }.sortedWith(SessionOrder)

    /**
     * Saves [record] unless the same is saved already; returns whether it wrote. A session analysed
     * again at another track leaves the old one, as its focus record does (FBBD-20).
     */
    fun save(record: SessionCoachingRecord): Boolean {
        val f = fileFor(record.trackId, record.sessionId)
        val name = checkedId(record.sessionId) + Suffix
        val elsewhere = CoreFiles.list(dir)
            .filter { it != record.trackId && CoreFiles.isDirectory(CoreFiles.join(dir, it)) }
            .map { CoreFiles.join(CoreFiles.join(dir, it), name) }.filter(CoreFiles::exists)
        elsewhere.forEach(CoreFiles::delete)
        if (read(f) == record) return elsewhere.isNotEmpty()
        writeAtomically(f, json.encodeToString(SessionCoachingRecord.serializer(), record))
        return true
    }

    /** Deletes every record of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        return CoreFiles.deleteRecursively(CoreFiles.join(dir, checkedId(trackId)))
    }

    private fun read(f: String): SessionCoachingRecord? {
        if (!CoreFiles.isFile(f)) return null
        return try {
            json.decodeFromString(SessionCoachingRecord.serializer(), CoreFiles.readText(f))
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
        CoreFiles.join(CoreFiles.join(dir, checkedId(trackId)), checkedId(sessionId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}

/** A track or session [id], once it is known to be safe as a file name. */
fun checkedId(id: String): String {
    require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_." } && id != "." && id != "..") {
        "bad id: $id"
    }
    return id
}

/** Writes [text] to a temporary file, syncs it and renames it over [target], so a reader never sees half of it. */
internal fun writeAtomically(target: String, text: String) = CoreFiles.writeTextAtomically(target, text)
