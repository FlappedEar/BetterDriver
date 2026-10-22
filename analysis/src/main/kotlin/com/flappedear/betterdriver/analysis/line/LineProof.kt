package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.ImprovedSeconds

// The loop's proof (FBBD-310): what came of the corners the last session's plan said to work on,
// in the session after it. Each focus corner's fastest pass in that session against the one
// before and against the target, through the same window at the same CornerId.

/** What came of working on one corner. */
enum class ProofVerdict {
    /** At least [ImprovedSeconds] faster than before. */
    Faster,

    /** Within [ImprovedSeconds] of before: GPS timing noise either way. */
    Same,

    /** At least [ImprovedSeconds] slower than before. */
    Slower,

    /** No pass through the corner in this session. */
    NotDriven,

    /** The track's corners changed between the two sessions (or had no identity): nothing to compare. */
    NotComparable,
}

/**
 * One focus corner of the plan before: its fastest pass in that session ([beforeSeconds]), in this
 * one ([afterSeconds]) and the target then ([targetSeconds]). [wonBackSeconds] is before minus
 * after (positive is faster); [leftSeconds] how much of the target is still to find, at least 0.
 * [cues]: how many corner cues the coach said there in this session, null when that is not known;
 * a corner that got faster with none was not the coach's doing ([coached]).
 */
data class CornerProof(
    val cornerId: String?,
    val name: String,
    val beforeSeconds: Double?,
    val afterSeconds: Double?,
    val targetSeconds: Double,
    val verdict: ProofVerdict,
    val cues: Int? = null,
) {
    /** Whether the coach spoke about this corner in this session. */
    val coached: Boolean get() = (cues ?: 0) > 0

    val wonBackSeconds: Double?
        get() = if (beforeSeconds != null && afterSeconds != null) beforeSeconds - afterSeconds else null
    val leftSeconds: Double? get() = afterSeconds?.let { (it - targetSeconds).coerceAtLeast(0.0) }
}

/** What came of [previous]'s focus in the session of [current]. */
data class LineProof(val previousSessionId: String, val corners: List<CornerProof>) {
    /** Time won back over the corners that could be compared; slower corners count against it. */
    val wonBackSeconds: Double get() = corners.mapNotNull { it.wonBackSeconds }.sum()
}

/**
 * The plan [current] was measured against: the latest of [plans] (one track's, any order) from a
 * session that started before it. Null without one.
 */
fun planBefore(plans: List<LinePlan>, current: LinePlan): LinePlan? = plans
    .filter { it.trackId == current.trackId && it.sessionStartUtcMillis < current.sessionStartUtcMillis }
    .maxWithOrNull(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))

/**
 * What came of the focus of [previous] (at most [count] corners, as [LinePlan.focus] picks them) in
 * the session of [current], both at the same track. Corners are matched by CornerId in one identity
 * revision; without that they are [ProofVerdict.NotComparable]. [cues] is the coach's corner cues of
 * the current session by CornerId (CoachEvents.cuesByCorner), null when not known. Null when
 * [previous] had no focus.
 */
fun lineProof(
    previous: LinePlan,
    current: LinePlan,
    cues: Map<String, Int>? = null,
    count: Int = LinePlan.FocusCorners,
): LineProof? {
    val focus = previous.focus(count)
    if (focus.isEmpty()) return null
    val comparable = previous.identityRevision != null && previous.identityRevision == current.identityRevision
    return LineProof(
        previous.sessionId,
        focus.map { before ->
            val after = current.corners.firstOrNull { it.cornerId != null && it.cornerId == before.cornerId }
            val was = before.sessionBestSeconds ?: before.reference.seconds
            val now = after?.sessionBestSeconds
            val verdict = when {
                !comparable || before.cornerId == null -> ProofVerdict.NotComparable
                now == null -> ProofVerdict.NotDriven
                was - now >= ImprovedSeconds -> ProofVerdict.Faster
                now - was >= ImprovedSeconds -> ProofVerdict.Slower
                else -> ProofVerdict.Same
            }
            val shown = verdict != ProofVerdict.NotComparable
            val said = before.cornerId?.let { id -> cues?.let { it[id] ?: 0 } }
            CornerProof(before.cornerId, before.name, was, now.takeIf { shown }, before.target.seconds, verdict, said)
        },
    )
}
