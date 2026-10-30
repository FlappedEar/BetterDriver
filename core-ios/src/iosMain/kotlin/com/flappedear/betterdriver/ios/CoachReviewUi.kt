package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CueCard

/**
 * The coach review of one session as the app shows it (FBBD-99), worded by the common coach code as
 * Android's CueReviewScreen: the [summary] ("12 cues · 5 of 8 corners better", or "No cues"), how many
 * cues were [notSaid] when any, and one card per cue said, in order.
 */
data class CoachReviewUi(val summary: String, val notSaid: String?, val cards: List<CueCard>)
