package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.summary.EarlierSession
import com.flappedear.betterdriver.analysis.summary.PreviousLaps
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import java.text.DateFormat
import java.util.Date

/** At most this many earlier sessions at the track are read, newest first. */
internal const val MaxEarlierSessions = 20

/** Of at most this many earlier sessions, at any track. */
internal const val MaxScannedSessions = 60

/** The earlier sessions at a track, newest first, and the session before in full. */
internal class Earlier(val sessions: List<EarlierSession>, val previous: PreviousLaps?)

/** A session's name on screen: when it started, in the phone's own date style. */
internal fun sessionLabel(startUtcMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startUtcMillis))

/**
 * The sessions recorded at [track] before session [id] (started at [startUtcMillis]), newest
 * first, with their laps against the track's current start/finish line. A session that names
 * another saved track is skipped without reading its samples; one that cannot be read is left out.
 */
@Suppress("TooGenericExceptionCaught")
internal fun earlierSessions(
    store: SessionStore,
    id: String,
    startUtcMillis: Long,
    track: Track,
    saved: List<Track>,
): Earlier {
    var previous: PreviousLaps? = null
    val sessions = store.list().asSequence()
        .filter { it.id != id && it.state != SessionState.RECORDING }
        .filter { it.info.startUtcMillis < startUtcMillis }
        .take(MaxScannedSessions)
        .mapNotNull { summary ->
            try {
                val named = savedTrackId(store.read(summary.dir, samples = false).events)
                if (named != null && named != track.id) return@mapNotNull null
                val data = store.read(summary.dir)
                if (trackFor(data, saved)?.id != track.id) return@mapNotNull null
                val session = telemetrySessionOf(data, track.startFinish)
                val laps = deriveSourceLapSession(session)
                // Only the session before keeps its samples, for its corner and straight times.
                if (previous == null && laps.fastestLapIndex != null) previous = PreviousLaps(session, laps)
                EarlierSession(sessionLabel(summary.info.startUtcMillis), laps)
            } catch (e: Exception) {
                android.util.Log.w("SessionAnalysis", "reading earlier session ${summary.id} failed", e)
                null
            }
        }
        .take(MaxEarlierSessions)
        .toList()
    return Earlier(sessions, previous)
}
