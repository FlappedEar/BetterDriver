package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.SessionFocusStore
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.coaches
import com.flappedear.betterdriver.coach.focusRecord
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.Track
import java.io.IOException

/**
 * Keeps what session [sessionId] at [trackId] recommends working on next (FBBD-20), from its
 * [analysis], for the coach of the next session at the track and for the coaching history.
 * [corners] are the coach picks of the analysis's corners. [epoch] is the app data's restore epoch
 * taken before the session was read. Written only when it changed; a failure is logged, never shown.
 */
@Suppress("LongParameterList") // The analysed session, as the analysis screen has it.
internal fun keepSessionFocus(
    store: SessionFocusStore,
    epoch: Long,
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    analysis: SessionAnalysis,
    corners: Map<String, CornerPick>,
) {
    // Read from the session before or during a restore ([epoch] taken before it was read), the
    // record is not written: the restored data wins (FBBD-156).
    val record = focusRecord(trackId, sessionId, startUtcMillis, nextSessionPlan(analysis), corners)
    try {
        var wrote = false
        AppDataGate.writeIfCurrent(epoch) { wrote = store.save(record) }
        if (wrote) DebugLog.i(Tag, "session focus of $sessionId: ${record.targets.joinToString { it.name }}")
    } catch (e: IOException) {
        DebugLog.w(Tag, "session focus of $sessionId not saved", e)
    }
}

private const val Tag = "SessionFocus"

/**
 * [this] saying whether the spoken coach will work on the next-session focus first (FBBD-20): only
 * at a [track] it coaches ([coaches]) and at [Verbosity.Focus]; Off and LapTimes say nothing about corners,
 * and Full speaks about every corner.
 */
internal fun AnalysisState.withCoachFocus(track: Track?, verbosity: Verbosity): AnalysisState {
    if (this !is AnalysisState.Ready) return this
    val first = track != null && coaches(track) && verbosity == Verbosity.Focus
    return AnalysisState.Ready(ui.copy(coachFocusFirst = first))
}
