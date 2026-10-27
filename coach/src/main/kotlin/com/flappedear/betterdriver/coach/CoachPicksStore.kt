package com.flappedear.betterdriver.coach

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The picked corners of each track, one `<track id>.json` in [dir] (FBBD-148). The app writes them from
 * the lap analysis; the coach reads them at each session start, so a change applies from the next
 * session. Writes are synced and renamed into place; a file that cannot be read counts as no picks.
 */
class CoachPicksStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(trackId: String): List<CornerPick> =
        read(fileFor(trackId))?.takeIf { it.trackId == trackId }?.picks.orEmpty()

    /** Every track with picks, by track id. */
    fun all(): Map<String, List<CornerPick>> = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        .mapNotNull { f -> read(f)?.takeIf { f.name == it.trackId + Suffix && it.picks.isNotEmpty() } }
        .sortedBy { it.trackId }
        .associate { it.trackId to it.picks }

    /** Saves [picks] for [trackId]; none deletes the file. */
    fun save(trackId: String, picks: List<CornerPick>) {
        val f = fileFor(trackId)
        if (picks.isEmpty()) {
            f.delete()
        } else {
            val text = json.encodeToString(CoachPicks.serializer(), CoachPicks(trackId = trackId, picks = picks))
            writeAtomically(f, text)
        }
    }

    private fun read(f: File): CoachPicks? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(CoachPicks.serializer(), f.readText())
                .takeIf { it.format == CoachPicks.Format && it.version == CoachPicks.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(trackId: String) = File(dir, checkedTrackId(trackId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}
