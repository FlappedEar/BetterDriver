package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.focus.NextSessionFocusAlgorithm
import com.flappedear.betterdriver.analysis.focus.NextSessionPlan
import com.flappedear.betterdriver.analysis.learning.SessionLearning
import com.flappedear.betterdriver.analysis.segments.FlaggableCorner

/**
 * Each of a session's [corners] as a coach pick (FBBD-148): its line on the fastest lap, and its
 * CornerId in the track's corner identity (FBBD-172) when the analysis's corners are the track's.
 * The same on Android and iOS (FBBD-424).
 */
fun coachCandidates(corners: List<FlaggableCorner>, sessionId: String): Map<String, CornerPick> =
    corners.associate { it.id to CornerPick(it.name, it.lats, it.lons, sessionId, it.cornerId, it.identityRevision) }

/**
 * What session [sessionId] at [trackId], started at [startUtcMillis], recommends working on next
 * (FBBD-20), as kept for the coach of the next session at the track and for the coaching history:
 * each focus under its CornerId when the analysis's corners are the track's, with its line from
 * [corners] (the coach picks of the analysis's corners, by segment id) so the coach can place it.
 * The same on Android and iOS (FBBD-424): the iPhone's coach works on it as Android's does.
 * With the session's [learning] (FBBD-417), it also proposes up to three exercises (FBBD-418); a gap
 * the driver explained after an exercise in an [earlier] record at the track is not proposed (FBBD-419).
 */
@Suppress("LongParameterList") // The session, what it recommends and the corners to place it.
fun focusRecord(
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    plan: NextSessionPlan,
    corners: Map<String, CornerPick>,
    learning: SessionLearning? = null,
    earlier: List<SessionFocusRecord> = emptyList(),
): SessionFocusRecord = focusTargets(sessionId, plan, corners).let { targets ->
    SessionFocusRecord(
        trackId = trackId,
        sessionId = sessionId,
        sessionStartUtcMillis = startUtcMillis,
        algorithm = NextSessionFocusAlgorithm,
        targets = targets,
        exercises = exerciseProposals(
            sessionId, learning, targets, corners, plan.identityRevision, earlier.filter { it.sessionId != sessionId },
        ),
    )
}

private fun focusTargets(
    sessionId: String,
    plan: NextSessionPlan,
    corners: Map<String, CornerPick>,
): List<FocusTarget> =
    plan.focuses.map { f ->
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
            timeConfidence = f.timeConfidence.name,
            changes = f.changes.map {
                FocusChangeRecord(it.metric.code, it.other, it.faster, it.unit, it.measured, it.confidence.name)
            },
            observation = f.area.observation,
            what = f.what,
            lats = line?.lats.orEmpty(),
            lons = line?.lons.orEmpty(),
        )
    }
