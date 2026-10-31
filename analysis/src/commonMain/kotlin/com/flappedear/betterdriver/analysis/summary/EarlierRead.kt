package com.flappedear.betterdriver.analysis.summary

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.savedTrackId
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.track.Track

/** At most this many earlier sessions at the track are read, newest first. */
const val MaxEarlierSessions = 20

/** Of at most this many earlier sessions, at any track. */
const val MaxEarlierScanned = 60

/**
 * The earlier sessions at a track, newest first, and the session before in full. [limited] is true
 * when older sessions were not read, so one of them could still hold a quicker lap.
 */
class EarlierSessions(val sessions: List<EarlierSession>, val previous: PreviousLaps?, val limited: Boolean)

/**
 * The sessions of [stored] (newest first) recorded at [track] before session [id] (started at
 * [startUtcMillis]), with their laps against the track's current start/finish line; the session
 * before keeps its samples for its corner and straight times (FBBD-122, FBBD-377). Android and iOS
 * read them the same way: a session that names another saved track is skipped without reading its
 * samples; one that cannot be read is left out and handed to [onError]. [read] reads a stored
 * session, with or without its samples; [label] names a session by its start.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // A damaged session never stops the others.
fun readEarlierSessions(
    stored: List<StoredSession>,
    id: String,
    startUtcMillis: Long,
    track: Track,
    saved: List<Track>,
    read: (StoredSession, Boolean) -> SessionData,
    label: (Long) -> String,
    onError: (StoredSession, Exception) -> Unit = { _, _ -> },
): EarlierSessions {
    var previous: PreviousLaps? = null
    val before = stored
        .filter { it.id != id && it.state != SessionState.RECORDING }
        .filter { it.info.startUtcMillis < startUtcMillis }
    val sessions = before.asSequence()
        .take(MaxEarlierScanned)
        .mapNotNull { summary ->
            try {
                val named = savedTrackId(read(summary, false).events)
                if (named != null && named != track.id) return@mapNotNull null
                val data = read(summary, true)
                if (trackFor(data, saved)?.id != track.id) return@mapNotNull null
                val session = telemetrySessionOf(data, track)
                val laps = deriveSourceLapSession(session)
                // Only the session before keeps its samples, for its corner and straight times.
                if (previous == null && laps.fastestLapIndex != null) previous = PreviousLaps(session, laps)
                // The others keep only their lap times: up to 4,096 trace points a lap add up.
                EarlierSession(label(summary.info.startUtcMillis), laps.copy(lapTraces = emptyList()))
            } catch (e: Exception) {
                onError(summary, e)
                null
            }
        }
        .take(MaxEarlierSessions)
        .toList()
    return EarlierSessions(sessions, previous, before.size > MaxEarlierScanned || sessions.size == MaxEarlierSessions)
}

/**
 * The session of [analysis] against [earlier], as the summary shows it on Android and iOS
 * (FBBD-122, FBBD-175): with each corner's and straight's typical time against the session before.
 */
fun progressAgainst(analysis: SessionAnalysis, earlier: EarlierSessions): SessionProgress =
    summarizeProgress(analysis.laps, earlier.sessions).copy(
        changes = earlier.previous?.let { segmentChanges(analysis, it) }.orEmpty(),
        earlierLimited = earlier.limited,
    )
