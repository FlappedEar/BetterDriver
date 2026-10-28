package com.flappedear.betterdriver.coach

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One reference file per track in [dir] (`<track id>.coach.json`). Writes are synced and renamed into
 * place. A file that cannot be read counts as no reference; it is rebuilt from the sessions.
 */
class CoachReferenceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(trackId: String): CoachReference? {
        val f = fileFor(trackId)
        if (!f.isFile || f.length() > MaxCoachFileBytes) return null
        return try {
            json.decodeFromString(CoachReference.serializer(), f.readText())
                .takeIf { it.format == CoachReference.Format && it.version == CoachReference.Version }
                ?.takeIf { it.trackId == trackId }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun save(reference: CoachReference) {
        writeAtomically(fileFor(reference.trackId), json.encodeToString(CoachReference.serializer(), reference))
    }

    fun delete(trackId: String) = fileFor(trackId).delete()

    /** Ids of the tracks with a reference file, readable or not. */
    fun trackIds(): List<String> = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        .map { it.name.removeSuffix(Suffix) }
        .filter { id -> runCatching { checkedTrackId(id) }.isSuccess }

    private fun fileFor(trackId: String): File = File(dir, "${checkedTrackId(trackId)}$Suffix")

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
internal fun writeAtomically(target: File, text: String) {
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
