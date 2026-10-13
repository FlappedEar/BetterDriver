package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryAlgorithm
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.Collections

/** At most this many sessions are analysed for the progression each time it opens, newest first. */
internal const val MaxProgressionAdds = 20

/** Of at most this many sessions, at any track, newest first. */
internal const val MaxProgressionScan = 120

/** Sessions already tried for a track since the app started ("track/session"), so none is analysed twice in vain. */
private val tried: MutableSet<String> = Collections.synchronizedSet(HashSet())

/** The coaching history of [trackId], none when it cannot be read. */
internal fun readHistory(store: CoachingHistoryStore, trackId: String): List<SessionCoachingRecord> = try {
    store.history(trackId)
} catch (e: IllegalArgumentException) {
    DebugLog.w(Tag, "coaching history at $trackId not read", e)
    emptyList()
}

/**
 * Which of [candidates] (session ids, newest first) to analyse for the progression: those whose
 * record is missing or older than this app's ([current] says whether one is current), not tried
 * already ([tried]), at most [max].
 */
internal fun sessionsToAdd(
    candidates: List<String>,
    current: (String) -> Boolean,
    tried: (String) -> Boolean,
    max: Int = MaxProgressionAdds,
): List<String> = candidates.asSequence().filter { !tried(it) && !current(it) }.take(max).toList()

/**
 * The sessions to analyse for the progression at track [trackId] (FBBD-181), newest first: those
 * with no current record in the coaching [history], not tried already, at most [MaxProgressionAdds].
 * Which of them are this track's is known only once each is read ([addToHistory]).
 */
internal fun sessionsToAdd(
    sessions: SessionStore,
    history: CoachingHistoryStore,
    trackId: String,
): List<SessionSummary> {
    val kept = readHistory(history, trackId).associateBy { it.sessionId }
    val summaries = sessions.list().filter { it.state != SessionState.RECORDING }.take(MaxProgressionScan)
    val todo = sessionsToAdd(
        summaries.map { it.id },
        current = { kept[it]?.algorithm == CoachingHistoryAlgorithm },
        tried = { "$trackId/$it" in tried },
    ).toSet()
    return summaries.filter { it.id in todo }
}

/**
 * Analyses [summary]'s session at track [trackId] and keeps its record in the coaching [history],
 * as opening its analysis would, against the restore [epoch] taken before it was read: the
 * progression then counts every session recorded or imported there, not only those opened since.
 * A session of another track is skipped after reading its events only; one that cannot be read or
 * analysed is left out and logged. Returns whether it was analysed.
 */
@Suppress("TooGenericExceptionCaught") // A damaged session never stops the others.
internal fun addToHistory(
    summary: SessionSummary,
    stores: ProgressionStores,
    trackId: String,
    epoch: Long,
): Boolean {
    tried += "$trackId/${summary.id}"
    return try {
        analyseInto(summary, stores, trackId, epoch)
    } catch (e: Exception) {
        DebugLog.w(Tag, "session ${summary.id} not added to the progression", e)
        false
    }
}

/** Where the progression reads sessions and tracks and keeps the coaching history. */
internal class ProgressionStores(
    val sessions: SessionStore,
    val tracks: TrackStore,
    val history: CoachingHistoryStore,
)

@Suppress("ReturnCount") // Another track's session, or none of a track at all: nothing to analyse.
private fun analyseInto(summary: SessionSummary, stores: ProgressionStores, trackId: String, epoch: Long): Boolean {
    val named = savedTrackId(stores.sessions.read(summary.dir, samples = false).events)
    if (named != null && named != trackId) return false
    val data = stores.sessions.read(summary.dir)
    val track = trackFor(data, stores.tracks.all())?.takeIf { it.id == trackId } ?: return false
    val start = data.info.startUtcMillis
    val analysis = analyzeAtTrack(stores.tracks, track, telemetrySessionOf(data, track), start)
    val events = data.events
    val coached = CoachedSession(CoachEvents.focusSource(events), CoachEvents.cuesByCorner(events))
    keepCoachingHistory(stores.history, epoch, trackId, data.id, start, analysis, coached, originOf(data.info)) {
        stores.tracks.get(trackId) != null
    }
    return true
}

private const val Tag = "Progression"
