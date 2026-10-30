package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.analysis.review.savedTrackId
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.analysis.summary.EarlierSession
import com.flappedear.betterdriver.analysis.summary.PreviousLaps
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track

/** At most this many earlier sessions at the track are read, newest first (as Android). */
private const val MaxEarlierSessions = 20

/** Of at most this many earlier sessions, at any track (as Android). */
private const val MaxEarlierScanned = 60

/**
 * Session [id] (started at [startUtcMillis], reviewed as [reviewed]) against the sessions recorded
 * before it at its track, as Android's summary card works it out (EarlierSessions, FBBD-122): newest
 * first, with their laps against the track's current start/finish line, the session before read in
 * full for its corner and straight times. A session that names another saved track is skipped
 * without reading its samples; one that cannot be read is left out. Null when the session is at no
 * track. [label] names a session by its start, as the review does.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // A damaged session never stops the others.
internal fun sessionProgress(
    store: SessionStore,
    reviewed: ReviewedSession,
    id: String,
    startUtcMillis: Long,
    saved: List<Track>,
    label: (Long) -> String,
): SessionProgress? {
    val track = reviewed.review.track ?: return null
    var previous: PreviousLaps? = null
    val before = store.list()
        .filter { it.id != id && it.state != SessionState.RECORDING }
        .filter { it.info.startUtcMillis < startUtcMillis }
    val sessions = before.asSequence()
        .take(MaxEarlierScanned)
        .mapNotNull { summary ->
            try {
                val named = savedTrackId(store.read(summary.id, samples = false).events)
                if (named != null && named != track.id) return@mapNotNull null
                val data = store.read(summary.id)
                if (trackFor(data, saved)?.id != track.id) return@mapNotNull null
                val session = telemetrySessionOf(data, track)
                val laps = deriveSourceLapSession(session)
                // Only the session before keeps its samples, for its corner and straight times.
                if (previous == null && laps.fastestLapIndex != null) previous = PreviousLaps(session, laps)
                // The others keep only their lap times: up to 4,096 trace points a lap add up.
                EarlierSession(label(summary.info.startUtcMillis), laps.copy(lapTraces = emptyList()))
            } catch (_: Exception) {
                null
            }
        }
        .take(MaxEarlierSessions)
        .toList()
    return reviewed.progress(sessions, previous, before.size > MaxEarlierScanned || sessions.size == MaxEarlierSessions)
}
