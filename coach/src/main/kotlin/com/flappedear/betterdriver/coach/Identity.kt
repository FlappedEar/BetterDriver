package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision

/** The direction of travel at an apex is taken over this far either side of it. */
internal const val ApexHeadingMeters = 5.0

/** The frame a [Track]'s laps are timed and traced in: the lap timer's, at its start/finish line. */
fun lapFrame(track: Track): LocalFrame? = track.startFinish?.let { LocalFrame(it.left) }

/**
 * [corners] of the reference lap [path] (in [frame]) with the track's corner identity (FBBD-172): each
 * braking corner is in the track corner its apex lies in, placed on the [identity]'s centreline by
 * position (on a straight, the nearest corner), and is said by that corner's number. Braking corners
 * in a chain of corners ("Corners 3–4") take its numbers in lap order. Without an identity, or for
 * an apex off the centreline, a corner keeps only the coach's own number.
 *
 * So the coach's "Turn 3" is the lap analysis's "Corner 3", whichever lap either of them is built on.
 */
fun identifyCorners(corners: List<Corner>, path: LapTrace, frame: LocalFrame, identity: TrackRevision?): List<Corner> {
    if (identity == null) return corners.map { it.copy(cornerId = null, turnNumber = null) }
    val taken = HashMap<String, Int>()
    return corners.map { c ->
        val apex = pointAt(path, c.apexAt)
        val heading = pointAt(path, c.apexAt + ApexHeadingMeters) - pointAt(path, c.apexAt - ApexHeadingMeters)
        val segment = identity.locate(frame, apex, heading)?.let { identity.cornerAt(it) }
        if (segment == null) {
            c.copy(cornerId = null, turnNumber = null)
        } else {
            val k = taken.merge(segment.id, 1, Int::plus)!! - 1
            c.copy(cornerId = segment.id, turnNumber = minOf(segment.number + k, segment.lastNumber))
        }
    }
}

/** [map] with its corners identified on [identity] ([identifyCorners]). */
fun identified(map: CornerMap, frame: LocalFrame, identity: TrackRevision?): CornerMap =
    CornerMap(map.path, identifyCorners(map.corners, map.path, frame, identity))
