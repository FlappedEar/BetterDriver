package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.readEarlierSessions
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track

/**
 * Session [id] (started at [startUtcMillis], reviewed as [reviewed]) against the sessions recorded
 * before it at its track, read by the shared core as on Android ([readEarlierSessions], FBBD-122,
 * FBBD-377). Null when the session is at no track. [label] names a session by its start.
 */
@Suppress("LongParameterList")
internal fun sessionProgress(
    store: SessionStore,
    reviewed: ReviewedSession,
    id: String,
    startUtcMillis: Long,
    saved: List<Track>,
    label: (Long) -> String,
): SessionProgress? {
    val track = reviewed.review.track ?: return null
    val earlier = readEarlierSessions(
        store.list(), id, startUtcMillis, track, saved, { s, samples -> store.read(s.id, samples) }, label,
    )
    return reviewed.progress(earlier)
}
