package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.math.hypot

/**
 * A reference lap for the predictive delta (FBBD-60): its path in the timer's local frame with the
 * time since the lap started at each point, from the start/finish crossing to the next one.
 *
 * A position on the current lap is matched to the nearest point of the reference path, and the
 * delta is the time the current lap has taken to get there minus the time the reference took.
 * Matching by position rather than by distance driven keeps the delta right when the two laps take
 * different lines. At the finish line both laps are at the end of the path, so the delta there is
 * the true lap time difference.
 */
class DeltaReference(points: List<Xy>, timesMicros: List<Long>) {
    private val xs = points.map { it.x }.toDoubleArray()
    private val ys = points.map { it.y }.toDoubleArray()
    private val ts = timesMicros.toLongArray()

    /** Distance along the path at each point, in metres. */
    private val along = DoubleArray(points.size)

    init {
        require(points.size == timesMicros.size && points.size >= 2) { "a reference needs at least two points" }
        for (i in 1 until points.size) along[i] = along[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val lapMicros: Long get() = ts.last()
    val lengthMeters: Double get() = along.last()

    /**
     * Follows one lap along this reference. Start a new one for each lap.
     *
     * Each match only looks a little behind the last one and ahead by about as far as the car has
     * moved since, so where the track doubles back close to itself (a hairpin, a parallel
     * straight) the match never jumps to the other leg. If nothing in that window is close, as
     * after leaving the pit lane, the whole path is searched.
     */
    inner class Cursor {
        private var along0 = 0.0
        private var last: Xy? = null

        /**
         * Reference time, in microseconds since its lap start, at the point nearest [p]; null when
         * [p] is more than [MaxOffPathMeters] from the reference path (pit lane, off track).
         */
        fun referenceMicros(p: Xy): Double? {
            val moved = last?.let { (p - it).length() } ?: 0.0
            last = p
            val lo = along0 - BackMeters
            val hi = along0 + moved * AheadFactor + AheadMarginMeters
            val match = nearest(p, lo, hi) ?: nearest(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                ?: return null
            along0 = along[match.segment] + match.u * (along[match.segment + 1] - along[match.segment])
            return ts[match.segment] + match.u * (ts[match.segment + 1] - ts[match.segment])
        }
    }

    private class Match(val segment: Int, val u: Double)

    /** Nearest point on the segments that overlap [lo, hi] metres along the path, if within reach. */
    private fun nearest(p: Xy, lo: Double, hi: Double): Match? {
        var bestSeg = -1
        var bestU = 0.0
        var bestD2 = MaxOffPathMeters * MaxOffPathMeters
        var i = 0
        while (i < xs.size - 1 && along[i + 1] < lo) i++
        while (i < xs.size - 1 && along[i] <= hi) {
            val ax = xs[i]
            val ay = ys[i]
            val dx = xs[i + 1] - ax
            val dy = ys[i + 1] - ay
            val len2 = dx * dx + dy * dy
            val u = if (len2 == 0.0) 0.0 else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val ex = ax + u * dx - p.x
            val ey = ay + u * dy - p.y
            val d2 = ex * ex + ey * ey
            if (d2 < bestD2) {
                bestD2 = d2
                bestSeg = i
                bestU = u
            }
            i++
        }
        return if (bestSeg < 0) null else Match(bestSeg, bestU)
    }

    companion object {
        /** Farther than this from the reference path, no delta is shown. */
        const val MaxOffPathMeters = 40.0

        private const val BackMeters = 10.0
        private const val AheadFactor = 1.5
        private const val AheadMarginMeters = 25.0
    }
}
