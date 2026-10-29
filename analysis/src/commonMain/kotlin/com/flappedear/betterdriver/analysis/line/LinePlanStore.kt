package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.checkedId
import com.flappedear.betterdriver.analysis.coaching.writeAtomically
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.filesEndingWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The line plans of each track, one `<track id>/<session id>.json` in [dir] per analysed session,
 * kept with the coach's files so a backup carries them (FBBD-156). The track's plan is its latest
 * session's ([get]); the earlier ones are what the next session is measured against (FBBD-310).
 * Writes are synced and renamed into place; a file that cannot be read counts as none. They hold
 * positions, so they go with their track.
 */
class LinePlanStore(private val dir: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The plan of [trackId]'s latest analysed session, or null without one. */
    fun get(trackId: String): LinePlan? = plans(trackId).lastOrNull()

    /** Every session's plan at [trackId], in the order they were driven. */
    fun plans(trackId: String): List<LinePlan> =
        CoreFiles.join(dir, checkedId(trackId)).let { d ->
            CoreFiles.filesEndingWith(d, Suffix)
                .mapNotNull { name ->
                    read(CoreFiles.join(d, name))?.takeIf { it.trackId == trackId && name == it.sessionId + Suffix }
                }
        }.sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))

    /** Saves [plan] as its session's unless the same is saved already; returns whether it wrote. */
    fun save(plan: LinePlan): Boolean {
        val f = fileFor(plan.trackId, plan.sessionId)
        if (read(f) == plan) return false
        writeAtomically(f, json.encodeToString(LinePlan.serializer(), plan))
        return true
    }

    /** Deletes every plan of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        return CoreFiles.deleteRecursively(CoreFiles.join(dir, checkedId(trackId)))
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; the next analysis writes it again.
    private fun read(f: String): LinePlan? {
        if (!CoreFiles.isFile(f)) return null
        return try {
            json.decodeFromString(LinePlan.serializer(), CoreFiles.readText(f))
                .takeIf { it.format == LinePlan.Format && it.version == LinePlan.Version }
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
