package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.track.SegmentKind
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
 *
 * Each also gets [Corner.entryAt], where its track corner starts on [path] (FBBD-242): the corner's
 * start placed on [path] as the lap analysis places it ([TrackRevision.place]), or where that cannot
 * be done, the apex less the centreline's metres from the corner's start to the apex. Only for an apex
 * inside its own corner and the first braking corner of a chain; the later ones of a chain start
 * inside it.
 */
fun identifyCorners(corners: List<Corner>, path: LapTrace, frame: LocalFrame, identity: TrackRevision?): List<Corner> {
    if (identity == null) return corners.map { it.copy(cornerId = null, turnNumber = null, entryAt = null) }
    val taken = HashMap<String, Int>()
    val length = identity.lengthMeters
    val starts = identity.place(frame, path.points, path.along)
    return corners.map { c ->
        val apex = pointAt(path, c.apexAt)
        val heading = pointAt(path, c.apexAt + ApexHeadingMeters) - pointAt(path, c.apexAt - ApexHeadingMeters)
        val at = identity.locate(frame, apex, heading)
        val segment = at?.let { identity.cornerAt(it) }
        if (at == null || segment == null) {
            c.copy(cornerId = null, turnNumber = null, entryAt = null)
        } else {
            val k = taken[segment.id] ?: 0
            taken[segment.id] = k + 1
            val inside = identity.segmentAt(at).id == segment.id
            val placed = starts?.get(identity.segments.indexOf(segment))?.takeIf { it <= c.apexAt }
            val entry = if (inside && k == 0) placed ?: (c.apexAt - (at - segment.startMeters).mod(length)) else null
            c.copy(
                cornerId = segment.id,
                turnNumber = minOf(segment.number + k, segment.lastNumber),
                entryAt = entry?.takeIf { it >= 0.0 },
            )
        }
    }
}

/**
 * [found] (the corners [findCorners] lists on the reference lap [path]) identified on [identity], with
 * one more for every corner of the track none of them is in (FBBD-405): the line plan has a target for
 * every track corner, so the coach needs a corner in each to measure and aim where the plan does. Each
 * is found in its track corner ([cornerIn]), from the corner's start to the next segment's on [path],
 * its approach from the previous corner's apex or [ApproachMeters] before the start. Without an
 * identity, or where its segments cannot be placed on [path], [found] identified as it is.
 */
@Suppress("ReturnCount") // Without an identity, without its places, without a corner to add.
fun withTrackCorners(found: List<Corner>, path: LapTrace, frame: LocalFrame, identity: TrackRevision?): List<Corner> {
    val named = identifyCorners(found, path, frame, identity)
    val starts = identity?.place(frame, path.points, path.along) ?: return named
    val have = named.mapNotNull { it.cornerId }.toSet()
    val added = identity.segments.withIndex()
        .filter { (_, s) -> s.kind == SegmentKind.Corner && s.id !in have }
        .mapNotNull { (i, _) ->
            val from = starts[i]
            val to = starts.getOrNull(i + 1) ?: path.lengthMeters
            // A corner across the start/finish line is left as it is: a lap's path ends there.
            if (to <= from) return@mapNotNull null
            val previousApex = found.filter { it.apexAt < from }.maxOfOrNull { it.apexAt }
            val approach = maxOf(0.0, from - ApproachMeters, previousApex?.plus(TrackCornerAfterApexMeters) ?: 0.0)
            cornerIn(path, from, to, approach)
        }
    if (added.isEmpty()) return named
    val apexes = added.map { it.apexAt }.toSet()
    val all = identifyCorners(numbered(found + added), path, frame, identity)
    // Kept only where it is said as the corner it was found for, not a corner the coach already had.
    val kept = all.filter { it.apexAt !in apexes || (it.cornerId != null && it.cornerId !in have) }
    return if (kept.size == all.size) all else identifyCorners(numbered(kept), path, frame, identity)
}

/** [corners] in lap order, numbered from 1 as [findCorners] numbers them. */
private fun numbered(corners: List<Corner>) =
    corners.sortedBy { it.apexAt }.mapIndexed { i, c -> c.copy(number = i + 1) }

/** A track corner's own approach starts no earlier than this after the previous corner's apex. */
private const val TrackCornerAfterApexMeters = 10.0

/** [map] with its corners identified on [identity] ([identifyCorners]). */
fun identified(map: CornerMap, frame: LocalFrame, identity: TrackRevision?): CornerMap =
    CornerMap(map.path, identifyCorners(map.corners, map.path, frame, identity))
