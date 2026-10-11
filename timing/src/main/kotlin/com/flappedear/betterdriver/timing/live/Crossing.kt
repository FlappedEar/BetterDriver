package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.TrackLine

/** Where and when the car crossed a line: [tMicros] on the session clock, [at] in the local frame. */
data class Crossing(val tMicros: Double, val at: Xy)

/**
 * A [TrackLine] in a [LocalFrame], ready for crossing tests (FBBD-57). Between two fixes the car is
 * taken to move in a straight line at constant speed, so the crossing time is interpolated
 * linearly; at 25 Hz that is exact for constant speed and well under 5 ms off for track-like
 * accelerations (tested).
 */
class Gate(line: TrackLine, frame: LocalFrame) {
    private val a = frame.toXy(line.left)
    private val ab = frame.toXy(line.right) - a
    private val lengthSquared = ab.dot(ab)

    /**
     * The crossing between fix ([p0], [t0]) and fix ([p1], [t1]), or null if the path does not cross
     * the line forwards. A fix exactly on the line counts once: the crossing is between the fix
     * before the line (or on it) and the first fix past it.
     */
    @Suppress("ReturnCount") // Guard clauses: each rules out one way of not crossing.
    fun crossing(p0: Xy, t0: Long, p1: Xy, t1: Long): Crossing? {
        if (lengthSquared == 0.0) return null
        // Positive on the far side of the line for a car crossing it forwards.
        val s0 = ab.cross(p0 - a)
        val s1 = ab.cross(p1 - a)
        if (!(s0 <= 0.0 && s1 > 0.0)) return null
        val f = s0 / (s0 - s1)
        val at = p0 + (p1 - p0) * f
        val u = (at - a).dot(ab) / lengthSquared
        if (u !in 0.0..1.0) return null
        return Crossing(t0 + f * (t1 - t0), at)
    }
}
