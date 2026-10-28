@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.PlannedTarget
import com.flappedear.betterdriver.coach.PlannedTargets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** Where the line plans are kept under the app's folder [base], as coach/lines on Android. */
internal fun linePlansDir(base: String) = "$base/coach/lines"

/**
 * The line plans of each track on the iPhone and iPad (FBBD-333), laid out as Android's LinePlanStore
 * keeps them: one `<track id>/<session id>.json` in [dir] per reviewed session. The track's plan is
 * its latest session's ([get]). A file that cannot be read counts as none.
 */
internal class IosLinePlanStore(private val dir: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The plan of [trackId]'s latest reviewed session, or null without one. */
    fun get(trackId: String): LinePlan? = plans(trackId).lastOrNull()

    /** Every session's plan at [trackId], in the order they were driven. */
    fun plans(trackId: String): List<LinePlan> {
        if (!safe(trackId)) return emptyList()
        val folder = "$dir/$trackId"
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, null).orEmpty()
            .filterIsInstance<String>().filter { it.endsWith(Suffix) }
        return names
            .mapNotNull { name ->
                read("$folder/$name")?.takeIf { it.trackId == trackId && name == it.sessionId + Suffix }
            }
            .sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))
    }

    /** Saves [plan] as its session's unless the same is saved already; returns whether it wrote. */
    fun save(plan: LinePlan): Boolean {
        val folder = "$dir/${plan.trackId}"
        val path = "$folder/${plan.sessionId}$Suffix"
        if (!safe(plan.trackId) || !safe(plan.sessionId) || read(path) == plan) return false
        NSFileManager.defaultManager.createDirectoryAtPath(folder, true, null, null)
        return NSString.create(string = json.encodeToString(LinePlan.serializer(), plan))
            .writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; the next review writes it again.
    private fun read(path: String): LinePlan? {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return try {
            json.decodeFromString(LinePlan.serializer(), text)
                .takeIf { it.format == LinePlan.Format && it.version == LinePlan.Version }
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

/**
 * The targets of [plan] in its corners with a CornerId, as the coach reference takes them: the
 * braking points beyond his best (FBBD-312) and the gear at the slowest point (FBBD-326). The same
 * as Android's plannedTargets (CoachReferences).
 */
internal fun plannedTargets(plan: LinePlan) = PlannedTargets(
    plan.sessionId,
    plan.corners.mapNotNull { c ->
        c.cornerId?.let {
            PlannedTarget(
                it, c.target.sessionId, c.target.lapSeconds,
                c.brakeRoom?.laterMeters ?: 0, c.brakeRoom?.seconds ?: 0.0, c.gear?.gear,
            )
        }
    },
    plan.gearbox,
)

/**
 * The focus of a line plan (FBBD-306): its corners as coach picks, most time to find first, and the
 * time to find in each by CornerId; made from session [sessionId].
 */
internal class PlanFocus(val sessionId: String, val picks: List<CornerPick>, val seconds: Map<String, Double>) {
    val cornerIds: List<String> get() = picks.mapNotNull { it.cornerId }
}

/**
 * [plan]'s focus as coach picks, as Android's CoachService takes it (UI-174 A); null when made from
 * session [current], without CornerIds or with nothing to do.
 */
internal fun planFocus(plan: LinePlan, current: String): PlanFocus? {
    val corners = plan.takeIf { it.sessionId != current && it.identityRevision != null }?.focus().orEmpty()
        .filter { it.cornerId != null && it.target.line.isNotEmpty() }
    if (corners.isEmpty()) return null
    val picks = corners.map { c ->
        CornerPick(
            c.name, c.target.line.map { it.latitude }, c.target.line.map { it.longitude }, plan.sessionId,
            c.cornerId, plan.identityRevision,
        )
    }
    // A braking point beyond his best (FBBD-312) is worth its step, a lower gear (FBBD-326) its laps.
    val seconds = corners.associate { c ->
        c.cornerId!! to
            (c.gainSeconds.takeIf { it > 0.0 } ?: c.brakeRoom?.seconds ?: c.gear?.seconds ?: c.gainSeconds)
    }
    return PlanFocus(plan.sessionId, picks, seconds)
}
