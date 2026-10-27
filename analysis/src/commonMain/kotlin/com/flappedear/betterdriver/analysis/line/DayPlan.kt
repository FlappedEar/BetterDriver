package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.ImprovedSeconds
import kotlinx.serialization.Serializable

// The day plan (FBBD-315, UI-214 B): what a track day found, kept for the next day at the track.
// Its corners are a line plan's, so the coach reads them as it reads one: the time is what a
// typical lap at the day's pace loses in each corner, the reference is that typical pass and the
// target the day's second fastest pass. The first session of the next day there works on its
// focus, and that day's page measures it ([dayProof]).

/**
 * One track day as kept: [day] is its date (ISO, the phone's zone), [sessionIds] its sessions in
 * the order they were driven. [plan] is made from the day's last session ([LinePlan.sessionId],
 * started at [LinePlan.sessionStartUtcMillis]); [typicalSeconds] is each corner's typical time at
 * the day's pace by its id in the plan (its CornerId when [LinePlan.identityRevision] is set).
 */
@Serializable
data class DayPlan(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val day: String,
    val sessionIds: List<String>,
    val plan: LinePlan,
    val typicalSeconds: Map<String, Double> = emptyMap(),
) {
    companion object {
        const val Format = "betterdriver-day-plan"
        const val Version = 1
    }
}

/**
 * [analysis] of [day] at [trackId] as its plan. [lastSessionId] and [lastStartUtcMillis] are the
 * day's last session's.
 */
fun dayPlan(
    analysis: DayAnalysis,
    trackId: String,
    day: String,
    lastSessionId: String,
    lastStartUtcMillis: Long,
): DayPlan {
    val line = LineAnalysis(
        analysis.referenceLapNumber,
        analysis.referenceLapSeconds,
        analysis.corners.map { c ->
            CornerLine(
                c.segmentId, c.name, c.turnsLeft, c.startMeters, c.endMeters, c.typical ?: c.bestLap, c.target,
                c.perLapGainSeconds, emptyList(), c.advice,
            )
        },
        analysis.sessions.sumOf { it.laps },
        analysis.sessions.size,
    )
    // No braking points beyond his best (FBBD-312): the day's focus is the corners a typical lap
    // loses, as the day's page shows them.
    val plan = linePlan(
        line, analysis.origin, trackId, lastSessionId, lastStartUtcMillis, analysis.identityRevision, beyond = null,
    )
    return DayPlan(
        trackId = trackId,
        day = day,
        sessionIds = analysis.sessions.map { it.id },
        plan = plan,
        typicalSeconds = analysis.corners.associate { it.segmentId to it.typicalSeconds },
    )
}

/** One focus corner of the day before, on this day: its typical pass at the day's pace then and now. */
data class DayCornerProof(
    val cornerId: String?,
    val name: String,
    val beforeSeconds: Double,
    val afterSeconds: Double?,
    val targetSeconds: Double,
    val verdict: ProofVerdict,
) {
    /** Before minus after: positive is faster on every lap. */
    val wonBackSeconds: Double? get() = afterSeconds?.let { beforeSeconds - it }
}

/** What came of day [previousDay]'s focus on the next day at the track. */
data class DayProof(val previousDay: String, val corners: List<DayCornerProof>) {
    /** Over the corners that could be compared; slower ones count against it. */
    val wonBackSeconds: Double get() = corners.mapNotNull { it.wonBackSeconds }.sum()
}

/**
 * What came of the focus of day [previous] (at most [count] corners, as [LinePlan.focus] picks them)
 * on day [current] at the same track: corners matched by CornerId in one identity revision, as the
 * line proof matches them (FBBD-310); without that they are [ProofVerdict.NotComparable]. Null when
 * [previous] had no focus.
 */
fun dayProof(previous: DayPlan, current: DayAnalysis, count: Int = LinePlan.FocusCorners): DayProof? {
    val focus = previous.plan.focus(count)
    if (focus.isEmpty()) return null
    val revision = previous.plan.identityRevision
    val comparable = revision != null && revision == current.identityRevision
    return DayProof(
        previous.day,
        focus.map { before ->
            val id = before.cornerId
            val after = current.corners.firstOrNull { it.segmentId == id }.takeIf { comparable && id != null }
            val was = previous.typicalSeconds[id] ?: before.reference.seconds
            val now = after?.typicalSeconds?.takeIf { after.passes > 0 }
            val verdict = when {
                !comparable || id == null -> ProofVerdict.NotComparable
                now == null -> ProofVerdict.NotDriven
                was - now >= ImprovedSeconds -> ProofVerdict.Faster
                now - was >= ImprovedSeconds -> ProofVerdict.Slower
                else -> ProofVerdict.Same
            }
            // The corner as this day names it: one CornerId may be numbered otherwise on another day's lap.
            DayCornerProof(id, after?.name ?: before.name, was, now, before.target.seconds, verdict)
        },
    )
}
