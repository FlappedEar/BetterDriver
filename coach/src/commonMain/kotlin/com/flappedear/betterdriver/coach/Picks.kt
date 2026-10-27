package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.MaxLatitude
import com.flappedear.betterdriver.timing.geo.MaxLongitude
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.TrackRevision
import kotlinx.serialization.Serializable

/**
 * A corner the driver picked in a session's lap analysis for the coach to work on next time
 * (FBBD-148). A pick names the track's corner by its CornerId ([cornerId], FBBD-172) in revision
 * [identityRevision] of the track's corner identity, and keeps where the corner is: its line on the
 * analysed session's fastest lap, latitude and longitude every few metres from where the corner
 * starts to where it ends, in driving order. Picks made before corner identities have only the line;
 * they are placed on the identity by it ([cornerIdIn]).
 */
@Serializable
data class CornerPick(
    /** The analysis's name for it, "Corner 3", as the coach settings page lists it. */
    val name: String,
    val lats: List<Double>,
    val lons: List<Double>,
    /** The session it was picked in. */
    val sessionId: String = "",
    val cornerId: String? = null,
    val identityRevision: Int? = null,
) {
    init {
        require(lats.isNotEmpty() && lats.size == lons.size) { "a pick needs a line" }
        // A damaged file must fail here, where it is read, not later in a frame or a match.
        require(lats.all { it.isFinite() && it in -MaxLatitude..MaxLatitude }) { "latitude out of range" }
        require(lons.all { it.isFinite() && it in -MaxLongitude..MaxLongitude }) { "longitude out of range" }
    }

    /** The line in [frame]. */
    fun line(frame: LocalFrame): List<Xy> = lats.indices.map { frame.toXy(lats[it], lons[it]) }

    /**
     * The same corner as [other], picked in another session or on another line: the same CornerId
     * of the same revision when both have one (FBBD-172); otherwise the middle of each line is within
     * [SamePickMeters] of the other line, running the same way there (the other leg of a hairpin can be
     * that close, driven the other way). Toggling a corner in the analysis uses it, so a corner picked
     * in one session shows as picked in every session at the track.
     */
    fun sameCorner(other: CornerPick): Boolean {
        if (cornerId != null && other.cornerId != null && identityRevision == other.identityRevision) {
            return cornerId == other.cornerId
        }
        val frame = LocalFrame(GeoPoint(lats.first(), lons.first()))
        val a = line(frame)
        val b = other.line(frame)
        return near(a, b) && near(b, a)
    }

    /**
     * The CornerId of the picked corner in [identity] (FBBD-172): its own id in the same revision, the
     * id it maps to from the revision before, or else the corner the middle of its line lies in.
     */
    fun cornerIdIn(identity: TrackRevision): String? = when {
        cornerId != null && identityRevision == identity.number -> cornerId
        cornerId != null && identityRevision == identity.number - 1 -> identity.previousIds[cornerId]
        else -> placedIn(identity)
    }

    /** The CornerId of the corner the middle of the line lies in. */
    private fun placedIn(identity: TrackRevision): String? {
        val frame = LocalFrame(identity.start)
        val line = line(frame)
        val i = line.size / 2
        val heading = line[minOf(i + 1, line.size - 1)] - line[maxOf(i - 1, 0)]
        return identity.locate(frame, line[i], heading.takeIf { it.length() > 0 })?.let { identity.cornerAt(it)?.id }
    }

    /** The middle of [line] is within [SamePickMeters] of [other], running the same way. */
    private fun near(line: List<Xy>, other: List<Xy>): Boolean {
        val i = line.size / 2
        val n = nearest(other, line[i])
        val heading = line[minOf(i + 1, line.size - 1)] - line[maxOf(i - 1, 0)]
        return n.distance <= SamePickMeters && sameWay(n.direction, heading)
    }
}

/** The middle of one pick's line is this close to another's for both to be the same corner. */
const val SamePickMeters = 15.0

/** A coach corner's apex is this close to a picked line for the corner to be picked. */
const val PickMatchMeters = 25.0

/** [picks] with [pick] added, or without it (and anything else at the same corner) if it was there. */
fun List<CornerPick>.toggled(pick: CornerPick): List<CornerPick> =
    if (any { it.sameCorner(pick) }) filterNot { it.sameCorner(pick) } else this + pick

/**
 * The corners of [map] the driver picked (FBBD-148), by the coach's number. With the track's corner
 * [identity] (FBBD-172) a coach corner is picked when it is in a picked track corner: the coach's
 * corners carry their CornerId ([identifyCorners]) and each pick names one ([CornerPick.cornerIdIn]).
 * A pick of a chain of corners picks every braking corner in it; a pick of a bend taken flat out
 * matches nothing and is never cued.
 *
 * Without an identity the corners are matched by position, never by number: a coach corner is picked
 * when its apex, the slowest point of the reference lap there, is within [PickMatchMeters] of a
 * picked line and the reference lap there runs the same way as the line (not a neighbouring stretch
 * of track driven the other way).
 */
fun pickedCorners(
    map: CornerMap,
    frame: LocalFrame,
    picks: List<CornerPick>,
    identity: TrackRevision? = null,
): Set<Int> = when {
    picks.isEmpty() -> emptySet()
    identity != null && map.corners.any { it.cornerId != null } -> {
        val ids = picks.mapNotNull { it.cornerIdIn(identity) }.toSet()
        map.corners.filter { it.cornerId in ids }.map { it.number }.toSet()
    }
    else -> pickedByLine(map, frame, picks)
}

/** The corners of [map] whose apex is near a picked line, running its way; for tracks with no identity. */
private fun pickedByLine(map: CornerMap, frame: LocalFrame, picks: List<CornerPick>): Set<Int> {
    val lines = picks.map { it.line(frame) }
    return map.corners.filter { c ->
        val apex = pointAt(map.path, c.apexAt)
        val heading = pointAt(map.path, c.apexAt + ApexHeadingMeters) - pointAt(map.path, c.apexAt - ApexHeadingMeters)
        lines.any { line ->
            val near = nearest(line, apex)
            near.distance <= PickMatchMeters && sameWay(near.direction, heading)
        }
    }.map { it.number }.toSet()
}

/** Two directions run the same way; an unknown or zero one (a line of one point) agrees with any. */
private fun sameWay(a: Xy?, b: Xy): Boolean = a == null || b.length() == 0.0 || a.dot(b) > 0

/** The point of [path] at [meters] along it, interpolated. */
internal fun pointAt(path: LapTrace, meters: Double): Xy {
    val m = meters.coerceIn(0.0, path.lengthMeters)
    val along = path.along
    var i = 0
    while (i < along.size - 2 && along[i + 1] < m) i++
    val span = along[i + 1] - along[i]
    val u = if (span <= 0.0) 0.0 else ((m - along[i]) / span).coerceIn(0.0, 1.0)
    return path.points[i] + (path.points[i + 1] - path.points[i]) * u
}

/** How far a point is from a line, and the line's direction there (null for a line of one point). */
private class Nearest(val distance: Double, val direction: Xy?)

private fun nearest(line: List<Xy>, p: Xy): Nearest {
    if (line.size == 1) return Nearest((p - line[0]).length(), null)
    var best = Nearest(Double.MAX_VALUE, null)
    for (i in 0 until line.size - 1) {
        val a = line[i]
        val ab = line[i + 1] - a
        val len2 = ab.dot(ab)
        val u = if (len2 <= 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        val d = (p - (a + ab * u)).length()
        if (d < best.distance) best = Nearest(d, ab.takeIf { len2 > 0.0 })
    }
    return best
}

/** One track's picks, as stored. */
@Serializable
data class CoachPicks(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val picks: List<CornerPick>,
) {
    companion object {
        const val Format = "betterdriver-coach-picks"
        const val Version = 1
    }
}
