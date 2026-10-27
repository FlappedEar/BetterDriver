package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.checkedId
import com.flappedear.betterdriver.analysis.coaching.writeAtomically
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The day plans of each track, one `<track id>/<day>.json` in [dir], kept with the coach's files so
 * a backup carries them (FBBD-156). Writes are synced and renamed into place; a file that cannot be
 * read counts as none. They hold positions, so they go with their track.
 */
class DayPlanStore(private val dir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every day's plan at [trackId], in the order they were driven. */
    fun days(trackId: String): List<DayPlan> =
        File(dir, checkedId(trackId)).listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
            .mapNotNull { f -> read(f)?.takeIf { it.trackId == trackId && f.name == checkedId(it.day) + Suffix } }
            .sortedWith(compareBy({ it.plan.sessionStartUtcMillis }, { it.day }))

    /** The plan of the latest day at [trackId] before [day] (ISO); null without one. */
    fun before(trackId: String, day: String): DayPlan? = days(trackId).lastOrNull { it.day < day }

    /** Saves [plan] as its day's unless the same is saved already; returns whether it wrote. */
    fun save(plan: DayPlan): Boolean {
        val f = File(File(dir, checkedId(plan.trackId)), checkedId(plan.day) + Suffix)
        if (read(f) == plan) return false
        writeAtomically(f, json.encodeToString(DayPlan.serializer(), plan))
        return true
    }

    /** Deletes every day plan of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        val d = File(dir, checkedId(trackId))
        d.deleteRecursively()
        return !d.exists()
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; opening the day writes it again.
    private fun read(f: File): DayPlan? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(DayPlan.serializer(), f.readText())
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
