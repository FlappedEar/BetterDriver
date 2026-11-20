package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanPass

/**
 * The targets of [plan] in its corners with a CornerId, as the coach reference takes them, with the
 * braking points beyond his best (FBBD-312, UI-276 B: "Turn 9, brake 10 later") and the gear at
 * the slowest point with the car's gearbox (FBBD-326, UI-279 A: "Turn 7, brake at 80, second").
 *
 * The coach aims only at a time driven twice (FBBD-177). Where nothing is ahead of the reference the
 * plan shows the reference's own pass, driven once: the coach aims at the corner's confirming pass
 * instead (F4), but for a braking point beyond his best, which is a step from his best on purpose.
 */
fun plannedTargets(plan: LinePlan) = PlannedTargets(
    plan.sessionId,
    plan.corners.mapNotNull { c ->
        c.cornerId?.let { id ->
            val driven = target(id, c.drivenTarget, c)
            val room = c.brakeRoom
            if (room == null) {
                driven
            } else {
                target(id, c.target, c, room.laterMeters, room.seconds, driven)
            }
        }
    },
    plan.gearbox,
)

@Suppress("LongParameterList") // A target's lap, step and gear, as PlannedTarget takes them.
private fun target(
    id: String,
    pass: PlanPass,
    c: PlanCorner,
    laterMeters: Int = 0,
    laterSeconds: Double = 0.0,
    driven: PlannedTarget? = null,
) = PlannedTarget(
    id, pass.sessionId, pass.lapSeconds, laterMeters, laterSeconds, c.gear?.gear, pass.lapStartSeconds, driven,
)

/**
 * The same targets with no braking point beyond his best (FBBD-415): only what he has driven. Those
 * steps are experimental targets, never demonstrated; at a track he visits for the first time the
 * coach does not ask for them.
 */
fun PlannedTargets.drivenOnly(): PlannedTargets = PlannedTargets(
    sessionId,
    targets.map {
        it.driven ?: PlannedTarget(
            it.cornerId, it.sessionId, it.lapSeconds, gear = it.gear, lapStartSeconds = it.lapStartSeconds,
        )
    },
    gearbox,
)

/** Whether any target asks for a braking point beyond his best (FBBD-312). */
val PlannedTargets.beyondBest: Boolean get() = targets.any { it.brakeLaterMeters > 0 }

/**
 * The focus of a line plan (FBBD-306): its corners as coach picks, most time to find first, and the
 * time to find in each by CornerId; made from session [sessionId].
 */
class PlanFocus(val sessionId: String, val picks: List<CornerPick>, val seconds: Map<String, Double>) {
    val cornerIds: List<String> get() = picks.mapNotNull { it.cornerId }
}

/**
 * [plan]'s focus as coach picks (UI-174 A); null when made from session [current], without CornerIds
 * or with nothing to do.
 */
fun planFocus(plan: LinePlan, current: String): PlanFocus? {
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
