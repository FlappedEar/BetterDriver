package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.coaching.coachingHistory
import com.flappedear.betterdriver.analysis.coaching.sessionCoachingRecord
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.analysis.line.ProofAsk
import com.flappedear.betterdriver.analysis.line.lineProof
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.coachingRows
import com.flappedear.betterdriver.feature.analysis.lastFocusWonBack
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.sharing.AppDataGate
import java.io.IOException
import java.time.ZoneId

/**
 * Keeps session [sessionId]'s record in the coaching history (FBBD-180): what was driven through
 * each corner of its [analysis] and what it recommends next, with what the coach did ([coached]).
 * [epoch] is the app data's restore epoch taken before the session was read: read before or during
 * a restore, the record is not written and the restored history wins (FBBD-156). Returns what the
 * session shows of the history: the record as just worked out, with the track's others as kept.
 * [trackKept] says whether the track is still saved when the record is written. [origin] is where
 * an imported session came from (FBBD-181), kept with the record so the progression can say so.
 * A failure is logged, never shown; nothing is shown for a session whose corners are not the track's.
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
): SessionCoaching {
    val record = try {
        sessionCoachingRecord(analysis, trackId, sessionId, startUtcMillis, coached, origin)
    } catch (e: IllegalArgumentException) {
        DebugLog.w(Tag, "coaching history of $sessionId not made", e)
        null
    } ?: return SessionCoaching()
    try {
        var wrote = false
        // Never for a track deleted meanwhile: a track made again under its id must not inherit it.
        AppDataGate.writeIfCurrent(epoch) { if (trackKept()) wrote = store.save(record) }
        if (wrote) DebugLog.i(Tag, "coaching history of $sessionId kept")
    } catch (e: IOException) {
        DebugLog.w(Tag, "coaching history of $sessionId not kept", e)
    } catch (e: IllegalArgumentException) {
        DebugLog.w(Tag, "coaching history of $sessionId not kept", e)
    }
    val others = try {
        store.history(trackId).filter { it.sessionId != sessionId }
    } catch (e: IllegalArgumentException) {
        DebugLog.w(Tag, "coaching history at $trackId not read", e)
        emptyList()
    }
    return coachingHistory(sessionId, others + record)
}

private const val Tag = "CoachingHistory"

/** Where a session imported from another app's file came from (FBBD-164): "VBO lap-12.vbo"; null when recorded here. */
internal fun originOf(info: SessionInfo): String? = info.imported?.let { "${it.format} ${it.fileName}".trim() }

/**
 * [this] with what came of the last focus and whether earlier gains held (FBBD-180), in the phone's
 * time zone; the focus corners [proof] measured through the line plans say it in seconds (FBBD-310).
 */
internal fun AnalysisState.withCoaching(coaching: SessionCoaching, proof: LineProof? = null): AnalysisState {
    if (this !is AnalysisState.Ready || coaching.empty) return this
    return AnalysisState.Ready(
        ui.copy(
            coaching = coachingRows(coaching, ZoneId.systemDefault(), proof),
            lastFocusWonBackSeconds = lastFocusWonBack(coaching, proof),
        ),
    )
}

/**
 * What came of the focus session [sessionId] worked on (FBBD-310, UI-191 A): its corners, as the
 * summary of the session they came from named them (UI-190 B), measured through that session's line
 * plan and this one's, both from [plans]; with the coach's cues at each. Null when either plan is
 * missing (a session analysed before line plans, or read during a restore).
 */
@Suppress("ReturnCount") // Each missing piece leaves the proof out.
internal fun lastFocusProof(
    plans: LinePlanStore,
    trackId: String,
    sessionId: String,
    coaching: SessionCoaching,
): LineProof? {
    val from = coaching.attempts.firstOrNull()?.source?.sessionId ?: return null
    val kept = try {
        plans.plans(trackId)
    } catch (e: IllegalArgumentException) {
        DebugLog.w(Tag, "line plans at $trackId not read", e)
        return null
    }
    val previous = kept.firstOrNull { it.sessionId == from } ?: return null
    val current = kept.firstOrNull { it.sessionId == sessionId } ?: return null
    val asked = coaching.attempts.map { ProofAsk(it.ask.cornerId, it.ask.name, it.cues) }
    return lineProof(previous, current, asked)
}
