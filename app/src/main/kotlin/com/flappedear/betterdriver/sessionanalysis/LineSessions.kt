package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.feature.analysis.LineState
import com.flappedear.betterdriver.feature.analysis.lineUi
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.Track
import java.io.IOException
import java.util.Locale

/** At most this many other sessions at the track give targets, newest first. */
internal const val MaxLineSessions = 20

/**
 * Every other session at [track] (FBBD-306), before and after session [id], newest first, with its
 * laps against the track's current start/finish line, read one at a time as the line analysis asks
 * for it, so no more than one is in memory at once. Of at most [MaxScannedSessions] sessions at any
 * track; one that names another saved track is skipped without reading its samples, and one that
 * cannot be read is left out.
 */
@Suppress("TooGenericExceptionCaught")
internal fun lineSessions(store: SessionStore, id: String, track: Track, saved: List<Track>): Sequence<LineSession> =
    store.list().asSequence()
        .filter { it.id != id && it.state != SessionState.RECORDING }
        .take(MaxScannedSessions)
        .mapNotNull { summary ->
            try {
                val named = savedTrackId(store.read(summary.dir, samples = false).events)
                if (named != null && named != track.id) return@mapNotNull null
                val data = store.read(summary.dir)
                if (trackFor(data, saved)?.id != track.id) return@mapNotNull null
                val session = telemetrySessionOf(data, track)
                val label = sessionLabel(summary.info.startUtcMillis)
                LineSession(session, deriveSourceLapSession(session), label, summary.id)
            } catch (e: Exception) {
                DebugLog.w(Tag, "reading session ${summary.id} for the line analysis failed", e)
                null
            }
        }
        .take(MaxLineSessions)

/**
 * Where to find time in session [id] (FBBD-306): its line analysis against [others], kept as the
 * track's line plan in [plans] for the coach and the 3D export, and shown in the Map tab. [epoch] is
 * the app data's restore epoch taken before the session was read: the plan of a session read before
 * a restore is not written. [onSaved] runs once a new plan is written, for the coach to aim at it.
 */
@Suppress("LongParameterList", "ReturnCount") // What one analysed session gives the plan.
internal fun lineState(
    plans: LinePlanStore,
    epoch: Long,
    track: Track,
    id: String,
    startUtcMillis: Long,
    analysis: SessionAnalysis,
    session: TelemetrySession,
    others: Sequence<LineSession>,
    onSaved: () -> Unit = {},
): LineState {
    val origin = analysis.axis?.origin ?: return LineState.Unavailable(NoLineText)
    val line = analyzeLine(analysis, session, others) ?: return LineState.Unavailable(NoLineText)
    val identity = analysis.identity?.number?.takeIf { analysis.identified }
    val plan = linePlan(line, origin, track.id, id, startUtcMillis, identity)
    try {
        var wrote = false
        AppDataGate.writeIfCurrent(epoch) { wrote = plans.save(plan) }
        if (wrote) {
            val focus = plan.focus().joinToString { String.format(Locale.ROOT, "%s %.2f s", it.name, it.gainSeconds) }
            DebugLog.i(Tag, "line plan of $id at ${track.id}: ${plan.sessions} sessions, focus $focus")
            onSaved()
        }
    } catch (e: IOException) {
        DebugLog.w(Tag, "line plan of $id not saved", e)
    }
    return lineUi(plan, id)?.let { LineState.Ready(it) } ?: LineState.Unavailable(NoLineText)
}

private const val Tag = "LinePlan"

private const val NoLineText =
    "No line analysis for this session: it needs a lap through the track's corners with GPS all the way round."
