package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.math.atan2

// The longest straight of a loop, where a track made from a session gets its line (FBBD-151).

/** The loop is smoothed over this many points on each side before straights are looked for. */
private const val SmoothPoints = 3

/** On a straight, the heading stays within a band this many degrees wide. */
private const val StraightBandDeg = 8.0

/** [loop] averaged over [SmoothPoints] on each side, wrapping round, to take out GNSS noise. */
internal fun smooth(loop: List<Xy>): List<Xy> {
    // The last point is the first one again; leave it out so the wrap is seamless.
    val ring = loop.subList(0, loop.size - 1)
    val n = ring.size
    val window = 2 * SmoothPoints + 1
    return List(n) { k ->
        var x = 0.0
        var y = 0.0
        for (o in -SmoothPoints..SmoothPoints) {
            val p = ring[(k + o).mod(n)]
            x += p.x
            y += p.y
        }
        Xy(x / window, y / window)
    }
}

/** The longest straight of a closed loop: its middle, its heading and its length. */
internal class Straight(val middle: Xy, val headingDeg: Double, val lengthMeters: Double)

/**
 * The longest run of [ring] (a closed loop, no point repeated) whose heading stays within a band of
 * [StraightBandDeg]. A chicane or a bend ends a straight; a straight may run across the loop's seam.
 */
@Suppress("ReturnCount") // Guard clauses: too short a loop, no straight at all.
internal fun longestStraight(ring: List<Xy>): Straight? {
    val n = ring.size
    if (n < 2 * ChordPoints + 2) return null
    val headings = DoubleArray(n) { headingAt(ring, it, wrap = true) }
    var bestFrom = 0
    var bestSteps = 0
    for (a in 0 until n) {
        var low = 0.0
        var high = 0.0
        var steps = 0
        while (steps < n - 1) {
            val t = turn(headings[a], headings[(a + steps + 1) % n])
            if (maxOf(high, t) - minOf(low, t) > StraightBandDeg) break
            low = minOf(low, t)
            high = maxOf(high, t)
            steps++
        }
        if (steps > bestSteps) {
            bestSteps = steps
            bestFrom = a
        }
    }
    if (bestSteps == 0) return null
    val start = ring[bestFrom]
    val end = ring[(bestFrom + bestSteps) % n]
    val middle = clearSpot(ring, bestFrom, bestSteps)
    val d = end - start
    val heading = (toDegrees(atan2(d.x, d.y)) + FullCircle) % FullCircle
    return Straight(middle, heading, bestSteps * SpacingMeters)
}

/**
 * The point of the straight of [steps] from [from] nearest its middle with no other part of the
 * loop within a line's width: where a figure-of-eight crosses over its straight, the line moves
 * along it, so the other branch never drives along the line. The middle when no point is clear.
 */
private fun clearSpot(ring: List<Xy>, from: Int, steps: Int): Xy {
    val n = ring.size
    val half = steps / 2
    val clear = DefaultLineWidthMeters * DefaultLineWidthMeters
    fun isClear(k: Int): Boolean {
        val p = ring[k % n]
        return (0 until n).all { q ->
            val along = (q - from).mod(n)
            val d = ring[q] - p
            along in 0..steps || d.dot(d) > clear
        }
    }
    val offsets = (0..half).asSequence().flatMap { o -> sequenceOf(o, -o) }
    val k = offsets.map { from + half + it }
        .firstOrNull { it in from..from + steps && isClear(it) }
        ?: (from + half)
    return ring[k % n]
}
