package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import kotlin.math.sqrt

// Position noise while parked, for the session validation report (FBBD-183), against FBBD-8.

private const val MaxStationaryStdMeters = 0.5

/** Below this speed the car is parked. */
private const val StationaryKmh = 1.0

/** The noise is measured over this long, from the start of each stop at least this long. */
private const val StationaryMicros = 60_000_000L

/** A stop with fixes further apart than this is two stops. */
private const val StationaryBreakMicros = 1_000_000L

private const val StationaryCriterion = "2D standard deviation <= 0.5 m over 60 s parked (FBBD-8)"

/**
 * Position noise while parked: for each stop of at least 60 s (speed under 1 km/h, no second
 * without a fix), the 2D standard deviation of its first 60 s of positions. The worst stop counts.
 */
internal fun stationaryNoise(s: GnssSamples): QualityMetric {
    val label = "Position noise parked"
    val stops = stops(s)
    if (stops.isEmpty()) return notMeasured("gnss.stationary", label, StationaryCriterion, "no stop of 60 s or more")
    val worst = stops.maxOf { std2d(s, it) }
    val how = "worst of ${stops.size} stop(s) of 60 s or more; positions within 60 s of each stop's start"
    val ok = worst <= MaxStationaryStdMeters
    return judged("gnss.stationary", label, worst, "m", ok, Basis.MEASURED, StationaryCriterion, how)
}

/** Index ranges of the first 60 s of each stop that lasted at least 60 s. */
private fun stops(s: GnssSamples): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    var last = -1
    fun close() {
        if (start >= 0 && s.t[last] - s.t[start] >= StationaryMicros) {
            out += start..(start..last).last { s.t[it] <= s.t[start] + StationaryMicros }
        }
        start = -1
    }
    for (i in 0 until s.size) {
        if (!s.hasFix(i)) continue
        val parked = s.speed[i] < StationaryKmh
        if (!parked || (start >= 0 && s.t[i] - s.t[last] > StationaryBreakMicros)) close()
        if (parked) {
            if (start < 0) start = i
            last = i
        }
    }
    close()
    return out
}

/** Root mean square distance of the fixes in [range] from their mean position, in metres. */
private fun std2d(s: GnssSamples, range: IntRange): Double {
    val fixes = range.filter { s.hasFix(it) }
    val frame = LocalFrame(GeoPoint(s.lat[fixes.first()], s.lon[fixes.first()]))
    val xy = fixes.map { frame.toXy(s.lat[it], s.lon[it]) }
    val mx = xy.sumOf { it.x } / xy.size
    val my = xy.sumOf { it.y } / xy.size
    return sqrt(xy.sumOf { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) } / xy.size)
}
