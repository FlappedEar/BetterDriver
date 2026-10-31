package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.lastFocusProof
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.progression.systemDayZone
import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.feature.analysis.CoachingCardUi
import com.flappedear.betterdriver.feature.analysis.coachingCard

/**
 * Where the coaching history (FBBD-180, CoachingHistoryStore, shared with Android) is kept under the
 * app's folder [base]: coach/history, as Android's filesDir/coach/history.
 */
internal fun coachingHistoryDir(base: String) = "$base/coach/history"

/**
 * The summary's coaching history card of [reviewed], session [id] (FBBD-364), as Android's analysis screen makes
 * it: the session's record is kept in [history], then what came of the focus it worked on is
 * measured through the line plans in [plans] (the review keeps this session's) and worded by the
 * shared core. [trackKept] says whether a track is still saved. Empty when there is nothing to show.
 */
@Suppress("ReturnCount") // No track, no history: no card.
internal fun sessionCoachingCard(
    reviewed: ReviewedSession,
    id: String,
    history: CoachingHistoryStore,
    plans: LinePlanStore,
    trackKept: (String) -> Boolean,
): CoachingCardUi {
    val trackId = reviewed.review.track?.id ?: return CoachingCardUi.None
    val coaching = reviewed.keepCoaching(history, CoachEvents.coachedSession(reviewed.events), trackKept)
    if (coaching.empty) return CoachingCardUi.None
    val proof = if (coaching.attempts.isEmpty()) null else lastFocusProof(plans, trackId, id, coaching)
    return coachingCard(coaching, systemDayZone(), proof)
}
