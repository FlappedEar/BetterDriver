package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.compare.ChartPoint

/** The value of [runs] at [meters], interpolated within a run; null outside every run. */
@Suppress("ReturnCount")
fun valueAt(runs: List<List<ChartPoint>>, meters: Double): Double? {
    for (run in runs) {
        if (run.isEmpty() || meters < run.first().distanceMeters || meters > run.last().distanceMeters) continue
        var i = 1
        while (i < run.size && run[i].distanceMeters < meters) i++
        if (i >= run.size) return run.last().value
        val a = run[i - 1]
        val b = run[i]
        val span = b.distanceMeters - a.distanceMeters
        return if (span <= 0.0) b.value else a.value + (b.value - a.value) * (meters - a.distanceMeters) / span
    }
    return null
}
