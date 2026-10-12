package com.flappedear.betterdriver.analysis.identity

import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentProposal
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.TrackRevision

// The lap analysis's side of the track's corner identity (FBBD-172): the analysis times its laps
// through the track's own corners and straights, placed on the axis of the session's fastest lap by
// position, so their ids and numbers stay the same whichever lap is the fastest. The first analysis
// at a track makes its identity from the corners and straights it proposes; a lap that no longer
// drives the track's shape makes the next revision.

/** The segment ids an analysis times its laps through, and the identity they come from. */
data class IdentifiedSegments(val segments: ApprovedSegments, val identity: TrackRevision?)

/** The revision tag of segments taken from track revision [number]. */
fun identityRevisionTag(number: Int) = "track-r$number"

/** [axis] as a path in a frame around its own origin, where its points already are. */
private class AxisPath(axis: ProgressAxis) {
    val frame = LocalFrame(GeoPoint(axis.origin.latitudeDegrees, axis.origin.longitudeDegrees))
    val points: List<Xy> = axis.points.map { Xy(it.eastMeters, it.northMeters) }
    val along: DoubleArray = axis.cumulative
}

/**
 * The segments of the fastest lap's [axis] (FBBD-172): [identity]'s, placed on the axis, when the lap
 * drives its shape; otherwise the [proposals] made on the axis, named by the identity's next revision
 * (or its first, without one). A track that became a circuit or a point-to-point course since its
 * identity was made gets the next revision with the axis's topology. Null without either.
 */
fun identifySegments(
    axis: ProgressAxis,
    proposals: List<TrackSegmentProposal>,
    identity: TrackRevision?,
): IdentifiedSegments? {
    val path = AxisPath(axis)
    val usable = path.points.size >= 2
    val placed = identity?.takeIf { usable }?.let { placeSegments(axis, path, it) }
    return when {
        placed != null -> IdentifiedSegments(placed, identity)
        !usable || proposals.size < 2 -> null
        else -> fromProposals(axis, path, proposals, identity)
    }
}

/** [proposals] named by [identity]'s next revision, or by a first one without it. */
private fun fromProposals(
    axis: ProgressAxis,
    path: AxisPath,
    proposals: List<TrackSegmentProposal>,
    identity: TrackRevision?,
): IdentifiedSegments {
    val shapes = proposals.map {
        val kind = if (it.type == TrackSegmentType.Corner) SegmentKind.Corner else SegmentKind.Straight
        SegmentShape(kind, it.start.progressMeters, it.end.progressMeters, maxOf(1, it.chainedCorners))
    }
    val made = identity?.revised(path.frame, path.points, shapes, path.along, axis.closed)
        ?: TrackRevision.first(path.frame, path.points, axis.closed, shapes, path.along)
    // The lap fits the identity but its corners could not be placed in order: the proposals as they are.
    val named = made.takeIf { it !== identity && it.segments.size == proposals.size }
    val segments = proposals.mapIndexed { i, p ->
        val s = named?.segments?.get(i)
        TrackSegment(s?.id ?: "s$i", s?.name ?: p.name, p.type, p.start.progressMeters, p.end.progressMeters)
    }
    val tag = named?.let { identityRevisionTag(it.number) } ?: "auto"
    return IdentifiedSegments(ApprovedSegments(segments, tag), named ?: identity)
}

/**
 * [identity]'s segments on [axis], in progress order, the last one wrapping across the line on a
 * loop; null when the lap does not drive the identity's shape or its boundaries cannot be placed in
 * order.
 */
private fun placeSegments(axis: ProgressAxis, path: AxisPath, identity: TrackRevision): ApprovedSegments? {
    val starts = identity.takeIf { it.closed == axis.closed && it.fits(path.frame, path.points) }
        ?.place(path.frame, path.points, path.along)
    val order = starts?.let { lapOrder(it, axis.closed) } ?: return null
    val first = order.first()
    val segments = order.mapIndexed { k, i ->
        val s = identity.segments[i]
        val start = if (!axis.closed && k == 0) 0.0 else starts[i]
        val end = when {
            k + 1 < order.size -> starts[order[k + 1]]
            !axis.closed || starts[first] == 0.0 -> axis.lengthMeters
            else -> starts[first]
        }
        val type = if (s.kind == SegmentKind.Corner) TrackSegmentType.Corner else TrackSegmentType.Straight
        TrackSegment(s.id, s.name, type, start, end)
    }
    return ApprovedSegments(segments, identityRevisionTag(identity.number))
}

/**
 * The segments in progress order, given where each starts: the lap order from the first one after the
 * line (on an open axis, the first one), or null when the starts are not in lap order.
 */
private fun lapOrder(starts: List<Double>, closed: Boolean): List<Int>? {
    val order = starts.indices.sortedBy { starts[it] }
    val first = order.first()
    val rotated = starts.indices.map { (first + it) % starts.size }
    val increasing = rotated.zipWithNext().all { (a, b) -> starts[b] > starts[a] }
    return rotated.takeIf { it == order && increasing && (closed || first == 0) }
}
