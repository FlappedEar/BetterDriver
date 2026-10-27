package com.flappedear.betterdriver.analysis.compare

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.progress.DeltaTiming
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.computeTimedDeltaSeries

/** Delta samples every this many metres. */
const val ComparisonStepMeters = 5.0

/** Most points kept per lap's speed trace. */
const val MaxSpeedPoints = 1500

/** One chart point: distance from the start/finish line and a value. */
data class ChartPoint(val distanceMeters: Double, val value: Double)

/**
 * Two laps of one session by distance on the session's axis (FBBD-102): [delta] is the other
 * lap's time minus the reference lap's (positive: the other lap is behind), [referenceSpeed] and
 * [otherSpeed] are km/h. Each list holds runs of continuous coverage; a GPS gap starts a new run.
 */
data class LapComparison(
    val referenceIndex: Int,
    val otherIndex: Int,
    val lengthMeters: Double,
    val delta: List<List<ChartPoint>>,
    val referenceSpeed: List<List<ChartPoint>>,
    val otherSpeed: List<List<ChartPoint>>,
)

/**
 * Compares timed laps [otherIndex] and [referenceIndex] (indices into the session's timed laps)
 * of [analysis], computed from [session]. Null when the session has no axis.
 */
@Suppress("ReturnCount")
fun compareLaps(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    referenceIndex: Int,
    otherIndex: Int,
): LapComparison? {
    val axis = analysis.axis ?: return null
    val laps = analysis.laps.timedLaps
    if (referenceIndex !in laps.indices || otherIndex !in laps.indices) return null
    val reference = laps[referenceIndex]
    val other = laps[otherIndex]
    val timing = DeltaTiming(
        other.startTelemetryTime, other.endTelemetryTime,
        reference.startTelemetryTime, reference.endTelemetryTime,
        axis.lengthMeters,
    )
    val delta = computeTimedDeltaSeries(
        analysis.traces[otherIndex], analysis.traces[referenceIndex], ComparisonStepMeters, timing,
    ).map { run -> run.map { ChartPoint(it.progressMeters, it.deltaSeconds) } }
    return LapComparison(
        referenceIndex,
        otherIndex,
        axis.lengthMeters,
        delta,
        speedByDistance(session, analysis.traces[referenceIndex]),
        speedByDistance(session, analysis.traces[otherIndex]),
    )
}

/**
 * The speed channel along a lap's projection, thinned evenly to at most [maxPoints]; a sample
 * with no speed ends a run.
 */
@Suppress("NestedBlockDepth")
fun speedByDistance(
    session: TelemetrySession,
    trace: List<ProgressSegment>,
    maxPoints: Int = MaxSpeedPoints,
): List<List<ChartPoint>> {
    if (session.channel(Aliases.SPEED) == null) return emptyList()
    val total = trace.sumOf { it.samples.size }
    val stride = maxOf(1, (total + maxPoints - 1) / maxPoints)
    val runs = mutableListOf<List<ChartPoint>>()
    var ordinal = 0
    for (segment in trace) {
        var run = mutableListOf<ChartPoint>()
        for (sample in segment.samples) {
            if (ordinal++ % stride != 0 && sample !== segment.samples.last()) continue
            val speed = if (sample.valid) session.valueAt(Aliases.SPEED, sample.telemetryTime) else null
            if (speed == null) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            } else {
                run += ChartPoint(sample.progressMeters, speed)
            }
        }
        if (run.isNotEmpty()) runs += run
    }
    return runs
}
