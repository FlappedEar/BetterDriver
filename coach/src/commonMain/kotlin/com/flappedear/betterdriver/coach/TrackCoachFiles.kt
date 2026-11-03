package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException

/**
 * The coach's files of each track under one coach folder [coachDir], laid out the same on Android
 * (filesDir/coach) and iOS (Application Support/coach): references, picked corners, session focus
 * records, coaching history, line plans and day plans.
 */
class TrackCoachFiles(coachDir: String) {
    private val references = CoachReferenceStore(CoreFiles.join(coachDir, "references"))
    private val picks = CoachPicksStore(CoreFiles.join(coachDir, "picks"))
    private val focus = SessionFocusStore(CoreFiles.join(coachDir, "focus"))
    private val history = CoachingHistoryStore(CoreFiles.join(coachDir, "history"))
    private val lines = LinePlanStore(CoreFiles.join(coachDir, "lines"))
    private val days = DayPlanStore(CoreFiles.join(coachDir, "days"))

    /**
     * Deletes track [trackId]'s coach reference, picked corners (FBBD-157), session focus records
     * (FBBD-20), coaching history (FBBD-180), line plan (FBBD-306) and day plan, before the track
     * itself goes, so a track made again under its id never picks up stale ones. The same on Android
     * and iOS. [failed] hears a part that could not be deleted. Returns whether none is left: only
     * then may the track be deleted.
     */
    fun delete(trackId: String, failed: (String, Exception) -> Unit = { _, _ -> }): Boolean {
        val parts = listOf(
            "coach reference" to { references.delete(trackId); references.get(trackId) == null },
            "coach picks" to { picks.save(trackId, emptyList()); picks.get(trackId).isEmpty() },
            "session focus" to { focus.delete(trackId) },
            "coaching history" to { history.delete(trackId) },
            "line plan" to { lines.delete(trackId) },
            "day plan" to { days.delete(trackId) },
        )
        // Every part is tried, so one that fails leaves no more behind than it must.
        return parts.map { (what, delete) ->
            try {
                delete()
            } catch (e: IOException) {
                failed(what, e)
                false
            } catch (e: IllegalArgumentException) {
                failed(what, e)
                false
            }
        }.all { it }
    }
}
