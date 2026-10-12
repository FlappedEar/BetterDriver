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
 */
fun analyzeKeepingIdentity(
    session: TelemetrySession,
    stored: TrackRevision?,
    store: (expected: TrackRevision?, made: TrackRevision) -> TrackRevision?,
): SessionAnalysis {
    var expected = stored
    var analysis = analyzeSession(session, identity = expected)
    var tries = 0
    var kept = false
    while (!kept && tries++ < MaxIdentityStoreTries) {
        val made = analysis.identity?.takeIf { it != expected }
        val now = made?.let { store(expected, it) }
        kept = now == null || now == made
        if (!kept) {
            expected = now
            analysis = analyzeSession(session, identity = now)
        }
    }
    return analysis
}
