package com.flappedear.betterdriver.timing.replay

import com.flappedear.betterdriver.timing.live.DeltaReference

/** The delta at one place of the reference lap: [deltaMicros] is other minus reference, positive when slower. */
data class DeltaPoint(val distanceMeters: Double, val referenceLapMicros: Long, val deltaMicros: Long)

/**
 * Two laps of the same track, from any sessions, matched by track position (FBBD-91), the way the
 * live delta matches the current lap to the reference ([DeltaReference]). Each fix of [other] is
 * matched to the nearest point of [reference]'s path, which gives pairs of lap times at the same
 * place. Both laps start at the start/finish line and end at it (point-to-point runs: at the start
 * line and the finish line, FBBD-155), so the pairs run from (0, 0) to
 * (reference lap time, other lap time), and the delta at the finish is the true lap time difference.
 *
 * Fixes that do not match (far off the reference path) or that would run backwards are skipped,
 * so both time series are strictly increasing and can be inverted.
 */
class LapAlignment(val reference: LapTrace, val other: LapTrace) {
    private val refTimes: DoubleArray
    private val otherTimes: DoubleArray

    init {
        val path = DeltaReference(reference.points.map { it.xy }, reference.points.map { it.lapMicros })
        val cursor = path.Cursor()
        val refEnd = reference.lap.timeMicros.toDouble()
        val otherEnd = other.lap.timeMicros.toDouble()
        val r = ArrayList<Double>(other.points.size)
        val o = ArrayList<Double>(other.points.size)
        r += 0.0
        o += 0.0
        for (p in other.points.subList(1, other.points.size - 1)) {
            val t = cursor.referenceMicros(p.xy) ?: continue
            val ot = p.lapMicros.toDouble()
            val ahead = t > r.last() && ot > o.last()
            val inside = t < refEnd && ot < otherEnd
            if (ahead && inside) {
                r += t
                o += ot
            }
        }
        r += refEnd
        o += otherEnd
        refTimes = r.toDoubleArray()
        otherTimes = o.toDoubleArray()
    }

    /** Lap time of [other] at the place [reference] was at [referenceLapMicros]; clamped to the laps. */
    fun otherMicrosAt(referenceLapMicros: Long): Long =
        Math.round(interpolate(refTimes, otherTimes, referenceLapMicros.toDouble()))

    /** Lap time of [reference] at the place [other] was at [otherLapMicros]; clamped to the laps. */
    fun referenceMicrosAt(otherLapMicros: Long): Long =
        Math.round(interpolate(otherTimes, refTimes, otherLapMicros.toDouble()))

    /** Other minus reference at the place [reference] was at [referenceLapMicros]. */
    fun deltaMicrosAt(referenceLapMicros: Long): Long {
        val t = referenceLapMicros.coerceIn(0, reference.lap.timeMicros)
        return otherMicrosAt(t) - t
    }

    /** The delta every [stepMeters] along the reference lap, from its start to its end, for a chart. */
    fun deltaByDistance(stepMeters: Double = DefaultStepMeters): List<DeltaPoint> {
        require(stepMeters > 0) { "step must be positive" }
        val length = reference.lengthMeters
        val n = (length / stepMeters).toInt()
        return (0..n).map { it * stepMeters }.let { if (it.last() < length) it + length else it }.map { d ->
            val t = reference.lapMicrosAtDistance(d)
            DeltaPoint(d, t, deltaMicrosAt(t))
        }
    }

    companion object {
        const val DefaultStepMeters = 5.0
    }
}

/** y at [x] on the polyline (xs, ys); xs strictly increasing; clamped at both ends. */
private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double = when {
    x <= xs.first() -> ys.first()
    x >= xs.last() -> ys.last()
    else -> interpolateInside(xs, ys, x)
}

private fun interpolateInside(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
    var lo = 0
    var hi = xs.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] <= x) lo = mid else hi = mid
    }
    val u = (x - xs[lo]) / (xs[hi] - xs[lo])
    return ys[lo] + u * (ys[hi] - ys[lo])
}
