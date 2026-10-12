package com.flappedear.betterdriver.analysis.identity

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.timing.track.TrackRevision

/** Times the stored identity changes under one analysis before it is shown with an unsaved one. */
const val MaxIdentityStoreTries = 3

/**
 * [session] analysed with the track's corner identity [stored] (FBBD-172), with the identity the
 * analysis made (the first, or the next revision) kept with the track. [store] is a compare-and-set:
 * it stores the made identity when the track's identity is still the one expected and returns the
 * identity stored after, or null when nothing could be written. When another analysis stored a
 * different one first, the session is analysed again with that one, and what that analysis makes is
 * stored the same way, up to [MaxIdentityStoreTries] times, so the corners shown are the corners kept.
 * [startUtcMillis] is when the session started: a session older than the one the stored identity was
 * made from (a layout changed since) is shown with the revision it makes, but the newer one is kept.
 */
fun analyzeKeepingIdentity(
    session: TelemetrySession,
    stored: TrackRevision?,
    startUtcMillis: Long? = null,
    store: (expected: TrackRevision?, made: TrackRevision) -> TrackRevision?,
): SessionAnalysis {
    var expected = stored
    var analysis = analyzeSession(session, identity = expected)
    var tries = 0
    var kept = false
    while (!kept && tries++ < MaxIdentityStoreTries) {
        val made = analysis.identity?.madeFrom(expected, startUtcMillis)?.takeIf { it != expected }
        val now = made?.takeUnless { expected.isNewerThan(startUtcMillis) }?.let { store(expected, it) }
        kept = now == null || now == made
        if (!kept) {
            expected = now
            analysis = analyzeSession(session, identity = now)
        }
    }
    return analysis
}

/** [this] identity, made from a session that started at [startUtcMillis], when it is not [input]. */
internal fun TrackRevision.madeFrom(input: TrackRevision?, startUtcMillis: Long?): TrackRevision =
    if (this == input || startUtcMillis == null) this else copy(madeFromUtcMillis = startUtcMillis)

/** Whether [this] identity was made from a session that started after [startUtcMillis]. */
internal fun TrackRevision?.isNewerThan(startUtcMillis: Long?): Boolean {
    val made = this?.madeFromUtcMillis
    return made != null && startUtcMillis != null && startUtcMillis < made
}
