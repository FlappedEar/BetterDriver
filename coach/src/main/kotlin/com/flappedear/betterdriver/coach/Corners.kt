package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One lap's path in the lap timer's local frame, from its start/finish crossing to the next one:
 * positions, microseconds since the lap started, and speed in km/h at each point. [speedsMeasured]
 * says at each point whether the speed came from the receiver or was worked out from positions
 * (FBBD-177); null when not known (a stored reference path).
 */
class LapTrace(
    val points: List<Xy>,
    val timesMicros: List<Long>,
    val speedsKmh: List<Double>,
    val speedsMeasured: List<Boolean>? = null,
) {
    init {
        require(points.size == timesMicros.size && points.size == speedsKmh.size) { "trace arrays differ in size" }
        require(speedsMeasured == null || speedsMeasured.size == points.size) { "trace arrays differ in size" }
        require(points.size >= 2) { "a trace needs at least two points" }
    }

    val lapMicros: Long get() = timesMicros.last()

    /** Metres from the start of the trace at each point. */
    val along: DoubleArray = DoubleArray(points.size).also { a ->
        for (i in 1 until points.size) a[i] = a[i - 1] + (points[i] - points[i - 1]).length()
    }

    val lengthMeters: Double get() = along.last()

    /** Time since the lap started at [meters] along the trace, interpolated. */
    fun timeAt(meters: Double): Double = at(meters) { timesMicros[it].toDouble() }

    /** Speed in km/h at [meters] along the trace, interpolated. */
    fun speedAt(meters: Double): Double = at(meters) { speedsKmh[it] }

    private inline fun at(meters: Double, value: (Int) -> Double): Double {
        val m = meters.coerceIn(0.0, lengthMeters)
        var lo = 0
        var hi = along.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (along[mid] <= m) lo = mid else hi = mid
        }
        val span = along[hi] - along[lo]
        val u = if (span <= 0.0) 0.0 else (m - along[lo]) / span
        return value(lo) + u * (value(hi) - value(lo))
    }

    /** The trace at every [stepMeters], interpolated linearly; repeated points are dropped. */
    fun resample(stepMeters: Double = ResampleStepMeters): Resampled {
        val n = (lengthMeters / stepMeters).toInt() + 1
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val vs = DoubleArray(n)
        val ts = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val s = k * stepMeters
            while (j < points.size - 2 && along[j + 1] < s) j++
            val span = along[j + 1] - along[j]
            val u = if (span <= 0.0) 0.0 else ((s - along[j]) / span).coerceIn(0.0, 1.0)
            xs[k] = points[j].x + u * (points[j + 1].x - points[j].x)
            ys[k] = points[j].y + u * (points[j + 1].y - points[j].y)
            vs[k] = speedsKmh[j] + u * (speedsKmh[j + 1] - speedsKmh[j])
            ts[k] = timesMicros[j] + u * (timesMicros[j + 1] - timesMicros[j])
        }
        return Resampled(stepMeters, xs, ys, vs, ts)
    }
}

/** A lap at a fixed distance step: position, speed (km/h) and time (µs since the lap started). */
class Resampled(
    val stepMeters: Double,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val speedsKmh: DoubleArray,
    val timesMicros: DoubleArray,
) {
    val size: Int get() = xs.size
    fun along(i: Int) = i * stepMeters
}

/** Distance step of the resampled lap the corners are found on. */
const val ResampleStepMeters = 2.0

/** A speed minimum is a corner only if the lap slowed at least this much for it. */
const val MinCornerDropKmh = 8.0

/**
 * A speed minimum below this is a stop (waiting past the line, a pit stop), never a corner: there
 * is nothing to say about braking for it, and the next corner's braking is looked for after it.
 */
const val StopKmh = 5.0

/**
 * The car brakes for a corner when it slows harder than this somewhere between its last speed peak
 * and the apex (0.25 g, as the coach's speech timing takes braking). Rolling to a stop or coasting
 * slows it far less.
 */
const val CornerBrakingMps2 = 2.5

/** Deceleration is measured over this long. */
private const val DecelWindowMicros = 400_000.0

private const val KmhPerMps = 3.6
private const val MicrosPerSecond = 1_000_000.0

/** A corner's minimum speed is the lowest within this distance either side. */
private const val MinimumWindowMeters = 40.0

/** Speed is averaged over this distance either side before looking for peaks and minima. */
private const val SmoothMeters = 6.0

/** The braking point is the last point within this of the peak speed before the corner. */
private const val PeakToleranceKmh = 0.5

/** Headings before and after the corner are taken over this distance. */
private const val HeadingSpanMeters = 20.0

/** Turning less than this from before braking to after the apex makes a corner [Turn.Straight]. */
private const val MinTurnDegrees = 20.0

private const val HalfTurnDegrees = 180.0
private const val FullTurnDegrees = 360.0

/**
 * The braking corners of one lap (FBBD-96, FBBD-202): every speed minimum that is the lowest speed
 * within 40 m either side, comes after a drop of at least [MinCornerDropKmh] from the fastest point
 * since the previous corner, and that the car braked into: it slowed harder than
 * [CornerBrakingMps2] on the way down. That fastest point is the braking point. Corners taken flat
 * out are not listed: there is nothing to say about braking for them. Nor are stops (below
 * [StopKmh]), such as a first lap that waits just past the line or calls at the pits.
 */
fun findCorners(trace: LapTrace): List<Corner> {
    val r = trace.resample()
    val smoothHalf = (SmoothMeters / r.stepMeters).toInt()
    val v = smooth(r.speedsKmh, smoothHalf)
    val w = (MinimumWindowMeters / r.stepMeters).toInt()
    val corners = mutableListOf<Corner>()
    var segmentStart = 0
    for (i in v.indices.filter { isMinimum(v, it, w) }) {
        var peak = segmentStart
        for (k in segmentStart..i) if (v[k] > v[peak]) peak = k
        // The latest point still at the peak speed: where braking actually starts after a flat top.
        var brake = peak
        for (k in peak..i) if (v[k] >= v[peak] - PeakToleranceKmh) brake = k
        // Smoothing rounds off the bottom of the speed trace; the apex is the slowest raw point near it.
        val apex = slowestNear(r.speedsKmh, i, smoothHalf)
        // A stop: the next corner's braking point comes after it, once the car is going again.
        val stop = r.speedsKmh[apex] < StopKmh
        if (stop) segmentStart = i
        if (stop || v[brake] - v[i] < MinCornerDropKmh || !braked(r, brake, apex)) continue
        corners += Corner(
            number = corners.size + 1,
            brakeAt = r.along(brake),
            apexAt = r.along(apex),
            entryKmh = r.speedsKmh[brake],
            minKmh = r.speedsKmh[apex],
            turn = turnAt(r, brake, apex),
        )
        segmentStart = i
    }
    return corners
}

/** Whether the car slowed harder than [CornerBrakingMps2] at some point from [from] to [to]. */
private fun braked(r: Resampled, from: Int, to: Int): Boolean {
    var j = 0
    for (k in from..to) {
        // The latest point at least a window before k.
        while (j + 1 <= k && r.timesMicros[k] - r.timesMicros[j + 1] >= DecelWindowMicros) j++
        val dt = (r.timesMicros[k] - r.timesMicros[j]) / MicrosPerSecond
        if (dt * MicrosPerSecond >= DecelWindowMicros &&
            (r.speedsKmh[j] - r.speedsKmh[k]) / KmhPerMps / dt >= CornerBrakingMps2
        ) {
            return true
        }
    }
    return false
}

private fun slowestNear(v: DoubleArray, i: Int, half: Int): Int {
    var best = i
    for (k in maxOf(0, i - half)..minOf(v.size - 1, i + half)) if (v[k] < v[best]) best = k
    return best
}

/** The first index of the lowest speed in [i - w, i + w]. */
private fun isMinimum(v: DoubleArray, i: Int, w: Int): Boolean {
    val lo = maxOf(0, i - w)
    val hi = minOf(v.size - 1, i + w)
    for (k in lo..hi) {
        if (v[k] < v[i] || (k < i && v[k] == v[i])) return false
    }
    // An edge of the lap is never a corner's minimum: the lap may simply start or end slow there.
    return i - w >= 0 && i + w < v.size
}

private fun smooth(v: DoubleArray, half: Int): DoubleArray = DoubleArray(v.size) { i ->
    val lo = maxOf(0, i - half)
    val hi = minOf(v.size - 1, i + half)
    var sum = 0.0
    for (k in lo..hi) sum += v[k]
    sum / (hi - lo + 1)
}

private fun turnAt(r: Resampled, brake: Int, apex: Int): Turn {
    val span = (HeadingSpanMeters / r.stepMeters).toInt()
    val b0 = maxOf(0, brake - span)
    val a1 = minOf(r.size - 1, apex + 2 * span)
    val a0 = minOf(r.size - 1, apex + span)
    val before = heading(r, b0, brake)
    val after = heading(r, a0, a1)
    var d = if (before == null || after == null) 0.0 else Math.toDegrees(after - before)
    while (d > HalfTurnDegrees) d -= FullTurnDegrees
    while (d < -HalfTurnDegrees) d += FullTurnDegrees
    return when {
        abs(d) < MinTurnDegrees -> Turn.Straight
        d > 0 -> Turn.Left // Counter-clockwise in an east/north frame.
        else -> Turn.Right
    }
}

/** Direction from point [i] to point [j] in radians, counter-clockwise from east; null if they coincide. */
private fun heading(r: Resampled, i: Int, j: Int): Double? {
    val dx = r.xs[j] - r.xs[i]
    val dy = r.ys[j] - r.ys[i]
    return if (hypot(dx, dy) < r.stepMeters / 2) null else atan2(dy, dx)
}
