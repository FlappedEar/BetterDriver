package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Values on the session clock, looked up at any time by linear interpolation (FBBD-152). Times are
 * strictly increasing. Outside the samples, or across a gap longer than [maxGapMicros] (the receiver
 * lost its fix, the session paused), there is no value: the overlay shows a dash, not a made-up one.
 */
internal class TimeSeries(
    private val times: LongArray,
    private val values: DoubleArray,
    private val maxGapMicros: Long = DefaultMaxGapMicros,
) {
    init {
        require(times.size == values.size) { "times and values differ in length" }
    }

    val size: Int get() = times.size

    fun at(tMicros: Long): Double? {
        if (times.isEmpty() || tMicros < times.first() || tMicros > times.last()) return null
        val i = floorIndex(times, tMicros)
        val j = minOf(i + 1, times.size - 1)
        val span = times[j] - times[i]
        return when {
            times[i] == tMicros -> values[i]
            span > maxGapMicros -> null
            else -> values[i] + (tMicros - times[i]).toDouble() / span * (values[j] - values[i])
        }
    }

    /** A moving average over [halfWindowMicros] either side of each sample: takes the buzz out of an accelerometer. */
    fun smoothed(halfWindowMicros: Long): TimeSeries {
        val out = DoubleArray(values.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in times.indices) {
            while (hi < times.size && times[hi] <= times[i] + halfWindowMicros) sum += values[hi++]
            while (times[lo] < times[i] - halfWindowMicros) sum -= values[lo++]
            out[i] = sum / (hi - lo)
        }
        return TimeSeries(times, out, maxGapMicros)
    }

    /** Each value through [f], at the same times. */
    fun map(f: (Double) -> Double): TimeSeries =
        TimeSeries(times, DoubleArray(values.size) { f(values[it]) }, maxGapMicros)

    companion object {
        /** Longer than any receiver's interval between fixes (1 Hz phones included), shorter than a lost fix. */
        const val DefaultMaxGapMicros = 2_000_000L

        /** From (time, value) pairs in any order; equal times keep the first, non-finite values are dropped. */
        fun of(samples: List<Pair<Long, Double>>, maxGapMicros: Long = DefaultMaxGapMicros): TimeSeries {
            val sorted = samples.filter { it.second.isFinite() }.sortedBy { it.first }
            val t = ArrayList<Long>(sorted.size)
            val v = ArrayList<Double>(sorted.size)
            for ((time, value) in sorted) {
                if (t.isEmpty() || time > t.last()) {
                    t += time
                    v += value
                }
            }
            return TimeSeries(t.toLongArray(), v.toDoubleArray(), maxGapMicros)
        }
    }
}

/** Index of the last time not after [t]; [times] is non-empty and starts at or before [t]. */
internal fun floorIndex(times: LongArray, t: Long): Int {
    var lo = 0
    var hi = times.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (times[mid] <= t) lo = mid else hi = mid - 1
    }
    return lo
}

/** Acceleration of the car in g: [longitudinal] positive when speeding up, [lateral] positive to the right. */
data class GForce(val longitudinal: Double, val lateral: Double)

/**
 * G from GNSS alone (FBBD-152), for receivers without an accelerometer: longitudinal from the change
 * of speed, lateral from speed times the turn rate of the path. Each fix looks [halfWindowMicros]
 * either side, which smooths GNSS noise much as a G meter's own filter would. Below [MinSpeedMs]
 * the heading is noise, so lateral G is zero there.
 *
 * [speedMs] is the receiver's speed at each fix in m/s, or null to take it from the path.
 */
internal fun gnssG(
    times: LongArray,
    path: List<Xy>,
    speedMs: DoubleArray?,
    halfWindowMicros: Long = GnssHalfWindowMicros,
): List<GForce?> {
    require(path.size == times.size && (speedMs == null || speedMs.size == times.size)) { "fixes differ in length" }
    val fixes = GnssFixes(times, path, speedMs)
    val out = ArrayList<GForce?>(times.size)
    var lo = 0
    var hi = 0
    for (i in times.indices) {
        while (times[lo] < times[i] - halfWindowMicros) lo++
        while (hi + 1 < times.size && times[hi + 1] <= times[i] + halfWindowMicros) hi++
        out += fixes.gAt(i, fixes.before(i, lo), fixes.after(i, hi))
    }
    return out
}

private class GnssFixes(val times: LongArray, val path: List<Xy>, val speedMs: DoubleArray?) {
    /** [lo], or for a slow receiver (1 Hz) with no fix inside the window, the one before if near enough. */
    fun before(i: Int, lo: Int) = if (lo == i && i > 0 && times[i] - times[i - 1] <= MaxNeighbourMicros) i - 1 else lo

    fun after(i: Int, hi: Int) =
        if (hi == i && i + 1 < times.size && times[i + 1] - times[i] <= MaxNeighbourMicros) i + 1 else hi

    /** G at fix [i] from the fixes [lo] and [hi] either side; null without one on each side. */
    fun gAt(i: Int, lo: Int, hi: Int): GForce? {
        if (lo == i || hi == i) return null
        val seconds = (times[hi] - times[lo]) / MicrosPerSecond
        val before = path[i] - path[lo]
        val after = path[hi] - path[i]
        val speedLo = speedMs?.get(lo) ?: (before.length() / ((times[i] - times[lo]) / MicrosPerSecond))
        val speedHi = speedMs?.get(hi) ?: (after.length() / ((times[hi] - times[i]) / MicrosPerSecond))
        val speed = speedMs?.get(i) ?: ((before.length() + after.length()) / seconds)
        // Speeds taken from the path are each a segment's mean, half a window apart in time.
        val speedSeconds = if (speedMs != null) seconds else seconds / 2
        val longitudinal = (speedHi - speedLo) / speedSeconds / StandardGravity
        val turning = speed >= MinSpeedMs && before.length() >= MinStepMeters && after.length() >= MinStepMeters
        val lateral = if (turning) {
            var turn = atan2(after.y, after.x) - atan2(before.y, before.x)
            if (turn > PI) turn -= 2 * PI
            if (turn < -PI) turn += 2 * PI
            // The two headings are half a window apart in time; a turn counter-clockwise (positive) is to the left.
            -speed * turn / (seconds / 2) / StandardGravity
        } else {
            0.0
        }
        return GForce(longitudinal, lateral)
    }
}

internal const val MicrosPerSecond = 1_000_000.0
internal const val StandardGravity = 9.80665
private const val GnssHalfWindowMicros = 400_000L
private const val MaxNeighbourMicros = 1_500_000L
private const val MinSpeedMs = 3.0
private const val MinStepMeters = 0.2
