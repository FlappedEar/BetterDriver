package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.filesEndingWith

/**
 * One reference file per track in [dir] (`<track id>.coach.json`). Writes are synced and renamed into
 * place. A file that cannot be read counts as no reference; it is rebuilt from the sessions. Shared by
 * the Android app and the iOS app (FBBD-359).
 */
class CoachReferenceStore(private val dir: String) {
    fun get(trackId: String): CoachReference? {
        val f = fileFor(trackId)
        if (!CoreFiles.isFile(f) || CoreFiles.size(f) > MaxCoachFileBytes) return null
        return try {
            CoachReferenceJson.decode(CoreFiles.readText(f), trackId)
        } catch (_: IOException) {
            null
        }
    }

    fun save(reference: CoachReference) {
        writeAtomically(fileFor(reference.trackId), CoachReferenceJson.encode(reference))
    }

    fun delete(trackId: String) = CoreFiles.delete(fileFor(trackId))

    /** Ids of the tracks with a reference file, readable or not. */
    fun trackIds(): List<String> = CoreFiles.filesEndingWith(dir, Suffix)
        .map { it.removeSuffix(Suffix) }
        .filter { id -> runCatching { checkedTrackId(id) }.isSuccess }

    private fun fileFor(trackId: String): String = CoreFiles.join(dir, "${checkedTrackId(trackId)}$Suffix")

    private companion object {
        const val Suffix = ".coach.json"
    }
}

/** [trackId], once it is known to be safe as a file name. */
internal fun checkedTrackId(trackId: String): String {
    require(trackId.isNotBlank() && trackId.all { it.isLetterOrDigit() || it in "-_" }) { "bad track id: $trackId" }
    return trackId
}

/**
 * The most a coach file read whole may hold (audit BD-03); a larger one, damaged or from elsewhere,
 * counts as missing instead of filling the memory at every read. Backups bring in no larger one.
 */
const val MaxCoachFileBytes = 8L shl 20

/** Writes [text] to a temporary file, syncs it and renames it over [target], so a reader never sees half of it. */
internal fun writeAtomically(target: String, text: String) = CoreFiles.writeTextAtomically(target, text)
