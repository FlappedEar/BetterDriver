package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.PlannedTarget
import com.flappedear.betterdriver.coach.PlannedTargets

/** Where the line plans (LinePlanStore, shared with Android) are kept under the app's folder [base]: coach/lines. */
internal fun linePlansDir(base: String) = "$base/coach/lines"

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
