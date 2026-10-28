@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.line.DayPlan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** Where the day plans are kept under the app's folder [base], as coach/days on Android. */
internal fun dayPlansDir(base: String) = "$base/coach/days"

/** The day plans beside the line plans in [linePlansDir] (coach/lines and coach/days). */
internal fun dayPlansBeside(linePlansDir: String) = linePlansDir.trimEnd('/').substringBeforeLast('/') + "/days"

/**
 * The day plans of each track on the iPhone and iPad (FBBD-315, FBBD-331), laid out as Android's
 * DayPlanStore keeps them: one `<track id>/<day>.json` in [dir] per track day opened. A file that
 * cannot be read counts as none.
 */
internal class IosDayPlanStore(private val dir: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every day's plan at [trackId], in the order they were driven. */
    fun days(trackId: String): List<DayPlan> {
        if (!safe(trackId)) return emptyList()
        val folder = "$dir/$trackId"
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, null).orEmpty()
            .filterIsInstance<String>().filter { it.endsWith(Suffix) }
        return names
            .mapNotNull { name -> read("$folder/$name")?.takeIf { it.trackId == trackId && name == it.day + Suffix } }
            .sortedWith(compareBy({ it.plan.sessionStartUtcMillis }, { it.day }))
    }

    /** The plan of the latest day at [trackId] before [day] (ISO); null without one. */
    fun before(trackId: String, day: String): DayPlan? = days(trackId).lastOrNull { it.day < day }

    /** Saves [plan] as its day's unless the same is saved already; returns whether it wrote. */
    fun save(plan: DayPlan): Boolean {
        val folder = "$dir/${plan.trackId}"
        val path = "$folder/${plan.day}$Suffix"
        if (!safe(plan.trackId) || !safe(plan.day) || read(path) == plan) return false
        NSFileManager.defaultManager.createDirectoryAtPath(folder, true, null, null)
        return NSString.create(string = json.encodeToString(DayPlan.serializer(), plan))
            .writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; opening the day writes it again.
    private fun read(path: String): DayPlan? {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return try {
            json.decodeFromString(DayPlan.serializer(), text)
                .takeIf { it.format == DayPlan.Format && it.version == DayPlan.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun safe(id: String) = id.isNotEmpty() && '/' !in id && id != "." && id != ".."

    private companion object {
        const val Suffix = ".json"
    }
}
