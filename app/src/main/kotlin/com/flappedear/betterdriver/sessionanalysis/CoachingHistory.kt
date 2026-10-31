package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.coachingRows
import com.flappedear.betterdriver.feature.analysis.lastFocusWonBack
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.analysis.coaching.keepCoachingHistory as keepShared

/**
 * Keeps session [sessionId]'s record in the coaching history (FBBD-180), as the shared core does on
 * both apps, against the app data's restore [epoch] taken before the session was read: read before
 * or during a restore, the record is not written and the restored history wins (FBBD-156).
 */
@Suppress("LongParameterList") // The analysed session, as the analysis screen has it.
internal fun keepCoachingHistory(
    store: CoachingHistoryStore,
    epoch: Long,
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    analysis: SessionAnalysis,
    coached: CoachedSession,
    origin: String? = null,
    trackKept: () -> Boolean = { true },
): SessionCoaching = keepShared(
    store, trackId, sessionId, startUtcMillis, analysis, coached, origin,
    write = { AppDataGate.writeIfCurrent(epoch, it) },
    trackKept = trackKept,
)

/**
 * [this] with what came of the last focus and whether earlier gains held (FBBD-180), in the phone's
 * time zone; the focus corners [proof] measured through the line plans say it in seconds (FBBD-310).
 */
internal fun AnalysisState.withCoaching(coaching: SessionCoaching, proof: LineProof? = null): AnalysisState {
    if (this !is AnalysisState.Ready || coaching.empty) return this
    return AnalysisState.Ready(
        ui.copy(
            coaching = coachingRows(coaching, proof = proof),
            lastFocusWonBackSeconds = lastFocusWonBack(coaching, proof),
        ),
    )
}
