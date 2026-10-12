package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.MaxLatitude
import com.flappedear.betterdriver.timing.geo.MaxLongitude
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Gate
import kotlin.math.abs

/** Shortest and longest loop taken for a circuit (FBBD-151), metres. */
const val MinLoopMeters = 800.0
const val MaxLoopMeters = 30_000.0

/** Loops whose lengths differ by at most this fraction are the same loop. */
internal const val SameLengthFraction = 0.03

/**
 * The loop counts as driven when at least this fraction of it was driven twice the same way: one
 * lap and a bit, not a road drive that happens to cross itself.
 */
private const val MinCoverage = 0.5

/** A track's radius reaches this far past the farthest point of its loop, metres. */
private const val RadiusMarginMeters = 500.0

/**
 * A closed loop the car drove again and again in one session (FBBD-151), as Garmin Catalyst's
 * "Add a Track" finds one: what a track made from the session would hold. [path] is one lap of the
 * loop, [startFinish] a line square to the car's heading in the middle of the loop's longest
 * straight ([straightMeters] long), crossed forwards [passes] times in the session. [center] is
 * the loop's centroid and [extentMeters] how far from it the loop reaches.
 */
data class SessionLoop(
    val path: List<GeoPoint>,
    val lengthMeters: Double,
    val center: GeoPoint,
    val extentMeters: Double,
    val startFinish: TrackLine,
    val straightMeters: Double,
    val passes: Int,
) {
    /** Laps the session would have with [startFinish]: one less than the passes. */
    val laps: Int get() = (passes - 1).coerceAtLeast(0)
}

/**
 * The circuit the session drove, from its fixes in time order, or null when it drove no closed
 * loop of [MinLoopMeters] to [MaxLoopMeters] at least once and a half in the same direction: a road
 * drive, a drive that crosses itself, a car that never left the paddock. Pure and quick enough for
 * a long session (a grid of places, not every pair of points), but call it off the main thread.
 */
@Suppress("ReturnCount") // Guard clauses: each says why there is no loop.
fun findSessionLoop(session: List<Fix>): SessionLoop? {
    // A damaged session can hold positions off the globe, or 0, 0 from a receiver without a fix.
    val fixes = session.filter(::isPosition).sortedBy { it.tMicros }
    if (fixes.size < 2) return null
    val frame = LocalFrame(GeoPoint(fixes.first().lat, fixes.first().lon))
    val runs = runsOf(smoothed(fixes, frame)).map(::resample).filter { it.size > 2 * ChordPoints + 1 }
    val returns = runs.flatMapIndexed { r, run -> returnsIn(run).map { (i, j) -> Return(r, i, j) } }
    if (returns.isEmpty()) return null
    val loopPoints = modalLength(returns.map { it.points }) ?: return null
    val same = returns.filter { abs(it.points - loopPoints) <= loopPoints * SameLengthFraction }
    if (same.size < loopPoints * MinCoverage) return null
    // The return nearest the usual length: one lap, typically not one through the pit lane.
    val chosen = same.minBy { abs(it.points - loopPoints) }
    val loop = runs[chosen.run].subList(chosen.from, chosen.to + 1)
    val straight = longestStraight(smooth(loop)) ?: return null
    val line = TrackLine.across(frame.toGeo(straight.middle), straight.headingDeg)
    val passes = forwardCrossings(fixes, line, frame)
    if (passes < 2) return null
    val centroid = Xy(loop.sumOf { it.x } / loop.size, loop.sumOf { it.y } / loop.size)
    return SessionLoop(
        path = loop.map(frame::toGeo),
        lengthMeters = (loop.size - 1) * SpacingMeters,
        center = frame.toGeo(centroid),
        extentMeters = loop.maxOf { (it - centroid).length() },
        startFinish = line,
        straightMeters = straight.lengthMeters,
        passes = passes,
    )
}

/** A real position: finite, on the globe, and not the 0, 0 a receiver reports without a fix. */
private fun isPosition(f: Fix): Boolean =
    f.lat.isFinite() && f.lon.isFinite() && f.lat in -MaxLatitude..MaxLatitude &&
        f.lon in -MaxLongitude..MaxLongitude && !(f.lat == 0.0 && f.lon == 0.0)

/**
 * The track to propose for [loop]: a saved circuit here that has no start/finish line yet gets it;
 * otherwise a new track, named after the directory circuit whose radius reaches the loop's centre
 * and linked to it, or else called [name]. Its radius reaches past the whole loop. A point-to-point
 * track here (FBBD-155) is left alone: the loop's line would become its start line, and the loop
 * would be timed as runs to its finish, or not at all without one.
 */
fun proposeTrack(
    loop: SessionLoop,
    saved: List<Track>,
    directory: CircuitList,
    name: String,
    id: String = newTrackId(),
): Track {
    fun reach(from: GeoPoint) = (distanceMeters(from, loop.center) + loop.extentMeters + RadiusMarginMeters)
        .coerceAtMost(Track.MaxRadiusMeters)
    val lineless = saved
        .filter { it.startFinish == null && !it.pointToPoint }
        .map { it to distanceMeters(loop.center, it.center) }
        .filter { (t, d) -> d <= t.radiusMeters }
        .minByOrNull { it.second }
        ?.first
    if (lineless != null) {
        return lineless.copy(
            startFinish = loop.startFinish,
            radiusMeters = maxOf(lineless.radiusMeters, reach(lineless.center)),
        )
    }
    val base = directory.find(loop.center)?.let { newTrackFrom(it, id) } ?: newTrackAt(loop.center, name, id)
    return base.copy(
        center = loop.center,
        startFinish = loop.startFinish,
        radiusMeters = maxOf(base.radiusMeters, reach(loop.center)),
    )
}

/** A later pass over the place of point [from] of run [run], at point [to]: one loop of [points] steps. */
private class Return(val run: Int, val from: Int, val to: Int) {
    val points: Int get() = to - from
}

/** How many times the fixes cross [line] forwards; a break in the fixes is not a crossing. */
private fun forwardCrossings(fixes: List<Fix>, line: TrackLine, frame: LocalFrame): Int {
    val gate = Gate(line, frame)
    var count = 0
    for (k in 1 until fixes.size) {
        val a = fixes[k - 1]
        val b = fixes[k]
        if (b.tMicros - a.tMicros > MaxGapMicros) continue
        if (gate.crossing(frame.toXy(a.lat, a.lon), a.tMicros, frame.toXy(b.lat, b.lon), b.tMicros) != null) count++
    }
    return count
}
