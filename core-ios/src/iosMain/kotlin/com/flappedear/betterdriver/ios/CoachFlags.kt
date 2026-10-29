package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.coach.CoachPicksStore
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.coaches
import com.flappedear.betterdriver.coach.pickedOf
import com.flappedear.betterdriver.coach.toggled
import com.flappedear.betterdriver.feature.analysis.AnalysisUi
import com.flappedear.betterdriver.feature.analysis.CornerRows
import com.flappedear.betterdriver.feature.analysis.firstCornerRows

// "Coach this next time" (FBBD-363, Android's FBBD-148) for the iPad and iPhone: a flag on a corner
// in Where time goes picks it for the spoken coach's next session at the track. The picks are kept
// in the shared CoachPicksStore beside the line plans (coach/picks), where the shared CoachRun reads them at the session start.

/** Where the picked corners (CoachPicksStore, shared with Android) are kept under the app's folder [base]. */
internal fun coachPicksDir(base: String) = "$base/coach/picks"

/**
 * The corners of a reviewed session that carry a coach flag ([pickable], by segment id) and those
 * flagged at its track ([picked]), in this session or another.
 */
class CoachFlags(val pickable: Set<String>, val picked: Set<String>) {
    /** The rows of Where time goes in [ui] that carry a flag: the first row of each flaggable corner. */
    fun rows(ui: AnalysisUi): CornerRows = firstCornerRows(ui.copy(coachPickable = pickable))

    fun isPicked(cornerId: String): Boolean = cornerId in picked

    /** Whether any row of Where time goes in [ui] carries a flag, so the note under it says what a flag does. */
    fun any(ui: AnalysisUi): Boolean = rows(ui).any

    /** The corner whose flag gain row [index] of [ui] carries, or null for none. */
    fun gainFlag(ui: AnalysisUi, index: Int): String? = ui.gains[index].cornerId?.takeIf { index in rows(ui).gains }

    /** The corner whose flag loss row [index] of [ui] carries, or null for none. */
    fun lossFlag(ui: AnalysisUi, index: Int): String? = ui.losses[index].cornerId?.takeIf { index in rows(ui).losses }

    companion object {
        val None = CoachFlags(emptySet(), emptySet())
    }
}

/**
 * The coach flags of [reviewed] (session [sessionId]) against the picks in [store]: none where the
 * coach does not run (no saved track, a point-to-point one, FBBD-155).
 */
internal class SessionCoachFlags(private val store: CoachPicksStore, reviewed: ReviewedSession, sessionId: String) {
    private val trackId = reviewed.review.track?.takeIf(::coaches)?.id
    private val identity = reviewed.identity
    private val candidates: Map<String, CornerPick> = if (trackId == null) {
        emptyMap()
    } else {
        reviewed.flaggableCorners.associate {
            it.id to CornerPick(it.name, it.lats, it.lons, sessionId, it.cornerId, it.identityRevision)
        }
    }

    fun flags(): CoachFlags {
        val id = trackId ?: return CoachFlags.None
        return CoachFlags(candidates.keys, pickedOf(candidates, store.get(id), identity))
    }

    /** Flags or unflags [cornerId] at the track; the flags as stored after. */
    fun toggle(cornerId: String): CoachFlags {
        val id = trackId
        val pick = candidates[cornerId]
        if (id != null && pick != null) store.save(id, store.get(id).toggled(pick))
        return flags()
    }
}
