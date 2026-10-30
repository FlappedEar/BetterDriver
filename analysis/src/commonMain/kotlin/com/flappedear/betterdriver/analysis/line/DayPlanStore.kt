package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.checkedId
import com.flappedear.betterdriver.analysis.coaching.writeAtomically
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.filesEndingWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The day plans of each track, one `<track id>/<day>.json` in [dir], kept with the coach's files so
 * a backup carries them (FBBD-156). Writes are synced and renamed into place; a file that cannot be
 * read counts as none. They hold positions, so they go with their track.
 */
class DayPlanStore(private val dir: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every day's plan at [trackId], in the order they were driven. */
    fun days(trackId: String): List<DayPlan> =
        CoreFiles.join(dir, checkedId(trackId)).let { d ->
            CoreFiles.filesEndingWith(d, Suffix)
                .mapNotNull { name ->
                    read(CoreFiles.join(d, name))
                        ?.takeIf { it.trackId == trackId && name == checkedId(it.day) + Suffix }
                }
        }.sortedWith(compareBy({ it.plan.sessionStartUtcMillis }, { it.day }))

    /** The plan of the latest day at [trackId] before [day] (ISO); null without one. */
    fun before(trackId: String, day: String): DayPlan? = days(trackId).lastOrNull { it.day < day }

    /** Saves [plan] as its day's unless the same is saved already; returns whether it wrote. */
    fun save(plan: DayPlan): Boolean {
        val f = CoreFiles.join(CoreFiles.join(dir, checkedId(plan.trackId)), checkedId(plan.day) + Suffix)
        if (read(f) == plan) return false
        writeAtomically(f, json.encodeToString(DayPlan.serializer(), plan))
        return true
    }

    /** Deletes every day plan of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        return CoreFiles.deleteRecursively(CoreFiles.join(dir, checkedId(trackId)))
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; opening the day writes it again.
    private fun read(f: String): DayPlan? {
        if (!CoreFiles.isFile(f)) return null
        return try {
            json.decodeFromString(DayPlan.serializer(), CoreFiles.readText(f))
                .takeIf { it.format == DayPlan.Format && it.version == DayPlan.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val Suffix = ".json"
    }
}
