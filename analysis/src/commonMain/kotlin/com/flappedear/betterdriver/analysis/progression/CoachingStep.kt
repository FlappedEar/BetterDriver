package com.flappedear.betterdriver.analysis.progression

import com.flappedear.betterdriver.analysis.coaching.FocusAttempt
import com.flappedear.betterdriver.analysis.coaching.FocusCheck
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.Persistence
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.coaching.coachingHistory

/**
 * The coaching at one point of a progression (FBBD-180's history, session by session): what came
 * of the focus each of its sessions worked on ([attempts]) and whether earlier gains held in them
 * ([checks]).
 */
data class CoachingStep(
    val key: String,
    val attempts: List<FocusAttempt> = emptyList(),
    val checks: List<FocusCheck> = emptyList(),
) {
    val improved: Int get() = attempts.count { it.verdict == FocusVerdict.Improved }

    /** Recommendations driven enough to tell: improved or not. */
    val measured: Int
        get() = attempts.count { it.verdict == FocusVerdict.Improved || it.verdict == FocusVerdict.NoGain }
    val held: Int get() = checks.count { it.persistence == Persistence.Persisted }
    val partlyHeld: Int get() = checks.count { it.persistence == Persistence.Partial }
    val lost: Int get() = checks.count { it.persistence == Persistence.Reverted }
    val empty: Boolean get() = attempts.isEmpty() && checks.isEmpty()
}

/**
 * The coaching history of each point of [progression], from [records], every record of its track
 * (those the filter left out too: a focus made before the first session shown is still the one a
 * shown session worked on).
 */
fun coachingProgression(progression: TrackProgression, records: List<SessionCoachingRecord>): List<CoachingStep> {
    val track = records.filter { it.trackId == progression.trackId }
    return progression.points.map { p ->
        val each = p.sessionIds.map { coachingHistory(it, track) }
        CoachingStep(p.key, each.flatMap { it.attempts }, each.flatMap { it.checks })
    }
}
