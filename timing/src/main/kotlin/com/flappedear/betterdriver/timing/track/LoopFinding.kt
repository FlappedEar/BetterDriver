package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

// Finding the loop a session drove again and again (FBBD-151), on the path in a local frame.

/** The path is resampled every this many metres before loops and straights are looked for. */
internal const val SpacingMeters = 5.0

/**
 * Fixes are averaged over this long on each side first: GNSS noise would otherwise make the path
 * zig-zag and look longer than it is. At track speeds that is a few tens of metres.
 */
private const val SmoothMicros = 500_000L

/** A fix this close to the one kept before is GNSS noise of a standing car, not driving. */
private const val MinStepMeters = 3.0

/** A longer pause between fixes, or a longer jump, breaks the path: no loop is measured across it. */
internal const val MaxGapMicros = 5_000_000L
private const val MaxJumpMeters = 150.0

/** A pass this soon after another (in path) is the same pass, not a return, metres. */
private const val SamePassMeters = 200.0

/** Two passes are over the same place when they come this close... */
private const val SamePlaceMeters = 15.0

/** ...going the same way, within this many degrees... */
private const val SameWayDeg = 30.0

/** ...and stay together for this far, metres. */
private const val TogetherMeters = 60.0

/** Headings are taken over a chord of this many resampled points on each side. */
internal const val ChordPoints = 4

internal const val FullCircle = 360.0
private const val HalfCircle = 180.0

/** A fix at [t] on the session clock, at [p] in the frame. */
internal class Timed(val t: Long, val p: Xy)

/**
 * Each fix moved to the average of the fixes within [SmoothMicros] of it, in the frame. A moving
 * window, so a long session costs one pass.
 */
internal fun smoothed(fixes: List<Fix>, frame: LocalFrame): List<Timed> {
    val xy = fixes.map { frame.toXy(it.lat, it.lon) }
    val out = ArrayList<Timed>(fixes.size)
    var lo = 0
    var hi = 0
    var sx = 0.0
    var sy = 0.0
    for (k in fixes.indices) {
        val t = fixes[k].tMicros
        while (hi < fixes.size && fixes[hi].tMicros <= t + SmoothMicros) {
            sx += xy[hi].x
            sy += xy[hi].y
            hi++
        }
        while (fixes[lo].tMicros < t - SmoothMicros) {
            sx -= xy[lo].x
            sy -= xy[lo].y
            lo++
        }
        val n = hi - lo
        out += Timed(t, Xy(sx / n, sy / n))
    }
    return out
}

/** The fixes split where the path breaks, with a standing car's noise left out. */
internal fun runsOf(fixes: List<Timed>): List<List<Xy>> {
    val runs = ArrayList<List<Xy>>()
    var run = ArrayList<Xy>()
    var lastT = Long.MIN_VALUE
    for (f in fixes) {
        val p = f.p
        val step = run.lastOrNull()?.let { (p - it).length() }
        if (step != null && (f.t - lastT > MaxGapMicros || step > MaxJumpMeters)) {
            runs += run
            run = ArrayList()
        }
        lastT = f.t
        if (run.isEmpty() || (p - run.last()).length() >= MinStepMeters) run += p
    }
    runs += run
    return runs.filter { it.size >= 2 }
}

/** Points every [SpacingMeters] along [path]. */
internal fun resample(path: List<Xy>): List<Xy> {
    val out = ArrayList<Xy>()
    out += path.first()
    var carried = 0.0 // Path since the last point put out.
    for (k in 1 until path.size) {
        val a = path[k - 1]
        val d = path[k] - a
        val len = d.length()
        var at = SpacingMeters - carried
        while (at <= len) {
            out += a + d * (at / len)
            at += SpacingMeters
        }
        carried = len - (at - SpacingMeters)
    }
    return out
}

/** Heading of [run] at point [k], degrees clockwise from north, over a chord around it. */
internal fun headingAt(run: List<Xy>, k: Int, wrap: Boolean = false): Double {
    val n = run.size
    val d = if (wrap) {
        run[Math.floorMod(k + ChordPoints, n)] - run[Math.floorMod(k - ChordPoints, n)]
    } else {
        run[minOf(k + ChordPoints, n - 1)] - run[maxOf(k - ChordPoints, 0)]
    }
    return (Math.toDegrees(atan2(d.x, d.y)) + FullCircle) % FullCircle
}

/** [b] minus [a] in degrees, in [-180, 180). */
internal fun turn(a: Double, b: Double): Double =
    ((b - a) % FullCircle + FullCircle + HalfCircle) % FullCircle - HalfCircle

/** Points of a path by grid cell of [SamePlaceMeters], so the points near a place are found quickly. */
private class PlaceGrid(private val points: List<Xy>) {
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        points.forEachIndexed { k, p -> cells.getOrPut(key(cellOf(p.x), cellOf(p.y))) { ArrayList() } += k }
    }

    /** Indices of the points in the cell of [p] and the eight around it: every point within [SamePlaceMeters]. */
    fun around(p: Xy): Sequence<Int> {
        val cx = cellOf(p.x)
        val cy = cellOf(p.y)
        return (-1L..1L).asSequence()
            .flatMap { dx -> (-1L..1L).asSequence().map { dy -> key(cx + dx, cy + dy) } }
            .flatMap { cells[it].orEmpty() }
    }

    private fun cellOf(v: Double) = floor(v / SamePlaceMeters).toLong()

    private fun key(cx: Long, cy: Long) = (cx shl Int.SIZE_BITS) xor (cy and LowBits)

    private companion object {
        const val LowBits = 0xffffffffL
    }
}

/**
 * For each point of [run], the first later point one loop on: as close, going the same way, and
 * staying with it for [TogetherMeters]. A point whose first return comes sooner than [MinLoopMeters]
 * is on a smaller loop (a roundabout, a paddock lap), so it has none: two laps of it are not a
 * circuit. Only points near each place are tried.
 */
internal fun returnsIn(run: List<Xy>): List<Pair<Int, Int>> {
    val minSteps = (MinLoopMeters / SpacingMeters).toInt()
    val samePass = (SamePassMeters / SpacingMeters).toInt()
    val maxSteps = (MaxLoopMeters / SpacingMeters).toInt()
    val follow = (TogetherMeters / SpacingMeters).toInt()
    val headings = DoubleArray(run.size) { headingAt(run, it) }
    val grid = PlaceGrid(run)
    fun samePlace(a: Int, b: Int) =
        (run[a] - run[b]).length() <= SamePlaceMeters && abs(turn(headings[a], headings[b])) <= SameWayDeg
    fun together(i: Int, j: Int) = j + follow < run.size && (0..follow).all { samePlace(i + it, j + it) }
    val found = ArrayList<Pair<Int, Int>>()
    for (i in run.indices) {
        val first = grid.around(run[i])
            .filter { j -> j - i in samePass..maxSteps }
            .sorted()
            .firstOrNull { j -> together(i, j) }
        if (first != null && first - i in minSteps..maxSteps) found += i to first
    }
    return found
}

/** The loop length (in points) most returns agree on, within [SameLengthFraction]. */
internal fun modalLength(lengths: List<Int>): Int? {
    val sorted = lengths.sorted()
    var bestCount = 0
    var best: Int? = null
    var hi = 0
    for (lo in sorted.indices) {
        val limit = sorted[lo] * (1 + 2 * SameLengthFraction)
        while (hi < sorted.size && sorted[hi] <= limit) hi++
        if (hi - lo > bestCount) {
            bestCount = hi - lo
            best = sorted[(lo + hi - 1) / 2]
        }
    }
    return best
}
