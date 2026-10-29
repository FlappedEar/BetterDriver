package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision

/** How a track brought from the other device compares with the one here (FBBD-80). */
enum class TrackMatch {
    /** No track with that id here: it is added without asking. */
    NEW,

    /** Same name, place and lines: nothing to do. */
    SAME,

    /** Both devices have the track and they differ: the user keeps one or the other. */
    DIFFERENT,
}

/**
 * How [incoming], brought from the other device, compares with the track of its id [here]: shared by
 * the Android app and the iOS app (FBBD-359), so both ask about the same tracks.
 */
fun trackMatch(incoming: Track, here: Track?): TrackMatch = when {
    here == null -> TrackMatch.NEW
    sameContent(incoming, here) -> TrackMatch.SAME
    else -> TrackMatch.DIFFERENT
}

/** Whether keeping [incoming] should take its corner identity too: it carries the driver's corrections (FBBD-179). */
fun carriesCorrectionsOf(incoming: Track): Boolean = incoming.identity?.corrected == true

/** Corner boundaries this close on the ground are the same place: a moved one is at least metres away. */
private const val SameBoundaryMeters = 1.0

/**
 * Revision, update time and corner identity (FBBD-172, made from laps on each device) are left
 * out: a track copied back and forth is still the same track. The driver's corner corrections
 * (FBBD-179) count when [incoming] has them: they are the driver's, not made from laps, so a
 * track whose corners were corrected on the other device is not the one here until this one has
 * the same corners, chicanes and names, starting at the same places.
 */
private fun sameContent(incoming: Track, here: Track) =
    incoming.copy(revision = 1, updatedUtcMillis = 0, identity = null) ==
        here.copy(revision = 1, updatedUtcMillis = 0, identity = null) &&
        (incoming.identity?.corrected != true || sameCorners(incoming.identity, here.identity))

/**
 * Whether [a] and [b] have the same corners and straights in lap order, as the driver reads them
 * and where each starts on the ground: comparable across devices, whose centrelines and metres
 * along them are each their own. A boundary the driver moved (FBBD-179) makes them differ.
 */
private fun sameCorners(a: TrackRevision?, b: TrackRevision?): Boolean {
    if (a == null || b == null || a.segments.size != b.segments.size) return a == b
    return a.segments.zip(b.segments).all { (x, y) ->
        x.type == y.type && x.name == y.name &&
            distanceMeters(a.pointAt(x.startMeters), b.pointAt(y.startMeters)) <= SameBoundaryMeters
    }
}
