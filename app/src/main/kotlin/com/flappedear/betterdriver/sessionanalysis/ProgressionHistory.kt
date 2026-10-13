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
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.Collections

/** At most this many of the track's sessions are analysed for the progression each time it opens, newest first. */
internal const val MaxProgressionAdds = 20

/** Of at most this many of the track's sessions, newest first; sessions at other tracks are not counted. */
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

/** Whether session [sessionId] was tried for the progression at track [trackId] since the app started. */
internal fun triedAt(trackId: String, sessionId: String): Boolean = "$trackId/$sessionId" in tried

/**
 * Goes through [candidates] (session ids, newest first) for the progression (FBBD-181), analysing
 * with [add] those whose record is missing or older than this app's ([current] says whether one is
 * current), not tried already ([tried]). [add] says whether the session was this track's: only the
 * track's sessions count towards the limits, so sessions at other tracks never use them up. It stops
 * after [maxScan] of the track's sessions (a current record says one is) or [maxAdds] added.
 * [left] hears, before each [add], how many more may be added at most. Returns how many were added.
 */
@Suppress("LongParameterList") // What to go through, how, and its limits.
internal suspend fun addNewest(
    candidates: List<String>,
    current: (String) -> Boolean,
    tried: (String) -> Boolean,
    add: suspend (String) -> Boolean,
    left: (Int) -> Unit = {},
    maxScan: Int = MaxProgressionScan,
    maxAdds: Int = MaxProgressionAdds,
): Int {
    val pending = candidates.count { !current(it) && !tried(it) }
    var passed = 0
    var seen = 0
    var added = 0
    for (id in candidates) {
        if (seen >= maxScan || added >= maxAdds) break
        if (current(id)) {
            seen++
        } else if (!tried(id)) {
            left(minOf(pending - passed, maxAdds - added))
            passed++
            if (add(id)) {
                seen++
                added++
            }
        }
    }
    left(0)
    return added
}

/**
 * The sessions to go through for the progression at track [trackId] (FBBD-181), newest first: every
 * one not recording, with the ids of those whose record in the coaching [history] is current. Which
 * of the others are this track's is known only once each is read ([addToHistory]).
 */
internal fun progressionCandidates(
    sessions: SessionStore,
    history: CoachingHistoryStore,
    trackId: String,
): Pair<List<SessionSummary>, Set<String>> {
    val current = readHistory(history, trackId).filter { it.algorithm == CoachingHistoryAlgorithm }
        .map { it.sessionId }.toSet()
    return sessions.list().filter { it.state != SessionState.RECORDING } to current
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
): Boolean = tryOnce("$trackId/${summary.id}", tried, epoch, { AppDataGate.epoch }) {
    try {
        analyseInto(summary, stores, trackId, epoch)
    } catch (e: Exception) {
        DebugLog.w(Tag, "session ${summary.id} not added to the progression", e)
        false
    }
}

/**
 * Runs [attempt] once for [key], kept in [tried] so it is not run again in vain, unless a backup
 * restore started since the restore [epoch] it was run against ([epochNow] differs, or a restore ran
 * then): its record was then not written (FBBD-156), and the session is tried again next time.
 */
internal fun <T> tryOnce(
    key: String,
    tried: MutableSet<String>,
    epoch: Long,
    epochNow: () -> Long,
    attempt: () -> T,
): T {
    tried += key
    val result = attempt()
    if (epoch % 2 != 0L || epochNow() != epoch) tried -= key
    return result
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
