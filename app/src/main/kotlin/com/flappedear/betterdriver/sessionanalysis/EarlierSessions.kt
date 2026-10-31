package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.summary.EarlierSessions
import com.flappedear.betterdriver.analysis.summary.MaxEarlierScanned
import com.flappedear.betterdriver.analysis.summary.readEarlierSessions
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.track.Track
import java.text.DateFormat
import java.util.Date

/** Of at most this many earlier sessions, at any track (the shared core's, as on iOS). */
internal const val MaxScannedSessions = MaxEarlierScanned

/** A session's name on screen: when it started, in the phone's own date style. */
internal fun sessionLabel(startUtcMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startUtcMillis))

/**
 * The sessions recorded at [track] before session [id] (started at [startUtcMillis]), newest
 * first, read by the shared core as on iOS ([readEarlierSessions], FBBD-377).
 */
internal fun earlierSessions(
    store: SessionStore,
    id: String,
    startUtcMillis: Long,
    track: Track,
    saved: List<Track>,
): EarlierSessions = readEarlierSessions(
    store.list().map { StoredSession(it.id, it.dir.path, it.info, it.state, it.durationMicros) },
    id,
    startUtcMillis,
    track,
    saved,
    read = { s, samples -> store.read(store.dir(s.id), samples) },
    label = ::sessionLabel,
    onError = { s, e -> DebugLog.w("SessionAnalysis", "reading earlier session ${s.id} failed", e) },
)
