package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.focus.NextSessionFocusAlgorithm
import com.flappedear.betterdriver.analysis.focus.NextSessionPlan
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.FocusChangeRecord
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.coach.SessionFocusStore
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.sharing.AppDataGate
import java.io.IOException

/**
 * Keeps what session [sessionId] at [trackId] recommends working on next (FBBD-20), from its
 * [analysis], for the coach of the next session at the track and for the coaching history.
 * [corners] are the coach picks of the analysis's corners. Written only when it changed; a failure
 * is logged, never shown.
 */
@Suppress("LongParameterList") // The analysed session, as the analysis screen has it.
internal fun keepSessionFocus(
    store: SessionFocusStore,
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    analysis: SessionAnalysis,
    corners: Map<String, CornerPick>,
) {
    // Made before or during a restore, the record is not written: the restored data wins (FBBD-156).
    val epoch = AppDataGate.epoch
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
 * What session [sessionId] at [trackId], started at [startUtcMillis], recommends working on next
 * (FBBD-20), as kept for the coach and the coaching history: each focus under its CornerId when the
 * analysis's corners are the track's, with its line from [corners] (the coach picks of the
 * analysis's corners, by segment id) so the coach can place it.
 */
internal fun focusRecord(
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    plan: NextSessionPlan,
    corners: Map<String, CornerPick>,
): SessionFocusRecord = SessionFocusRecord(
    trackId = trackId,
    sessionId = sessionId,
    sessionStartUtcMillis = startUtcMillis,
    algorithm = NextSessionFocusAlgorithm,
    targets = plan.focuses.map { f ->
        val line = corners[f.segmentId]
        FocusTarget(
            id = FocusTarget.idOf(sessionId, f.segmentId),
            role = f.role.code,
            segmentId = f.segmentId,
            cornerId = f.segmentId.takeIf { plan.identityRevision != null },
            identityRevision = plan.identityRevision,
            name = f.name,
            kind = f.area.kind.code,
            confidence = f.confidence.level.name,
            timeAvailableSeconds = f.timeAvailableSeconds,
            changes = f.changes.map { FocusChangeRecord(it.metric.code, it.other, it.faster, it.unit, it.measured) },
            observation = f.area.observation,
            what = f.what,
            lats = line?.lats.orEmpty(),
            lons = line?.lons.orEmpty(),
        )
    },
)
