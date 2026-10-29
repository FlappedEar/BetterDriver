package com.flappedear.betterdriver.analysis.progression

import com.flappedear.betterdriver.analysis.focus.FocusChangeBrakingMeters
import com.flappedear.betterdriver.analysis.focus.FocusChangeSpeedFraction
import com.flappedear.betterdriver.analysis.focus.FocusMinimumLossSeconds
import kotlin.math.abs

/** A trend needs a value at this many points at least: two points are a change, not a trend. */
const val MinimumTrendPoints = 3

/** A lap time moving less than this from the first point to the last is steady: lap timing noise. */
const val SteadyLapSeconds = 0.10

/**
 * The trend of [values] of [metric]: the change from the first to the last of the points with a
 * value, along their least-squares line by point index (so one odd point moves it less than a
 * first-to-last difference would), and whether that is a change at all: at least a corner's
 * [FocusMinimumLossSeconds], [SteadyLapSeconds] of a lap, [FocusChangeBrakingMeters] of a pedal point
 * or [FocusChangeSpeedFraction] of a speed, the thresholds the focus and the coaching history use.
 * Null below [MinimumTrendPoints] values.
 */
internal fun trendOf(metric: ProgressionMetric, values: List<MetricValue?>): Trend? {
    val points = values.mapIndexedNotNull { i, v -> v?.let { i.toDouble() to it.value } }
    if (points.size < MinimumTrendPoints) return null
    val mx = points.sumOf { it.first } / points.size
    val my = points.sumOf { it.second } / points.size
    val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
    val slope = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
    val span = points.last().first - points.first().first
    val change = slope * span
    val firstFitted = my + slope * (points.first().first - mx)
    val steady = abs(change) < threshold(metric, firstFitted)
    val direction = if (steady) 0 else if (change > 0) 1 else -1
    val better = metric.lowerIsBetter?.takeIf { !steady }?.let { lower -> (change < 0) == lower }
    return Trend(change, direction, points.size, values.filterNotNull().all { it.precise }, better)
}

private fun threshold(metric: ProgressionMetric, first: Double): Double = when (metric) {
    ProgressionMetric.BestLap, ProgressionMetric.MedianLap -> SteadyLapSeconds
    ProgressionMetric.LapSpread, ProgressionMetric.LapDeviation,
    ProgressionMetric.CornerTime, ProgressionMetric.CornerSpread,
    -> FocusMinimumLossSeconds
    ProgressionMetric.BrakingPoint, ProgressionMetric.ThrottlePickup -> FocusChangeBrakingMeters
    ProgressionMetric.MinimumSpeed, ProgressionMetric.ExitSpeed -> FocusChangeSpeedFraction * abs(first)
}
