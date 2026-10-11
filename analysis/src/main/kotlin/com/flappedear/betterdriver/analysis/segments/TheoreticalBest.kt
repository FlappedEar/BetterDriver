@file:Suppress("ReturnCount", "LongParameterList", "CyclomaticComplexMethod", "ComplexCondition")

package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.geoMidpoint
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.buildProgressAxis
import com.flappedear.betterdriver.analysis.progress.projectLapTrace

// Port of FlappedEar Telemetry analysis/theoretical_best.dart and the sector part of
// outing_theoretical_best.dart (Apache 2.0): for each approved segment, the fastest time any lap
// of the population actually recorded, so each segment's winner is a real, attributable result.

/** The algorithm a [TheoreticalBestLap] is stamped with. */
const val TheoreticalBestAlgorithm = "theoretical-best-v1"

/** No approved segments to measure sectors against. */
const val TheoreticalBestNoApprovedSegmentation = "noApprovedSegmentation"

/** At least one segment has no valid time on any lap; the total is withheld. */
const val TheoreticalBestIncompleteCoverage = "incompleteCoverage"

/** The fastest time through one approved segment. */
data class TheoreticalBestSector(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    val seconds: Double? = null,
    /** The lap that recorded it. */
    val sourceLapReference: Any? = null,
    val unavailableReason: String = "",
)

/** A theoretical best lap: the fastest time of every approved segment. */
data class TheoreticalBestLap(
    val stamp: SegmentationStamp = SegmentationStamp(),
    val sectors: List<TheoreticalBestSector> = emptyList(),
    /** Only when every sector has a time. */
    val totalSeconds: Double? = null,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

/**
 * The fastest valid time of each segment of [approved] across [population], the laps' own sector
 * times. A result for another revision or track configuration is ignored; the first lap with the
 * fastest time wins a tie. Pass only laps that take part in ranking.
 */
fun computeTheoreticalBest(approved: ApprovedSegments, population: List<LapSectorTimes>): TheoreticalBestLap {
    if (approved.revision.isEmpty() || approved.segments.isEmpty()) {
        return TheoreticalBestLap(unavailableReason = TheoreticalBestNoApprovedSegmentation)
    }
    var total = 0.0
    var complete = true
    val sectors = approved.segments.map { segment ->
        var seconds: Double? = null
        var source: Any? = null
        for (lap in population) {
            if (!lap.valid ||
                lap.stamp.revision != approved.revision ||
                lap.stamp.trackConfigurationReference != approved.trackConfigurationReference
            ) {
                continue
            }
            for (candidate in lap.sectors) {
                val time = candidate.seconds
                if (candidate.segmentId != segment.id || time == null) continue
                if (seconds == null || time < seconds) {
                    seconds = time
                    source = lap.lapReference
                }
            }
        }
        if (seconds == null) complete = false else total += seconds
        TheoreticalBestSector(
            segment.id, segment.name, segment.type, seconds, source,
            if (seconds == null) TheoreticalBestIncompleteCoverage else "",
        )
    }
    return TheoreticalBestLap(
        stamp = stampOf(approved, TheoreticalBestAlgorithm),
        sectors = sectors,
        totalSeconds = if (complete) total else null,
        unavailableReason = if (complete) "" else TheoreticalBestIncompleteCoverage,
        valid = true,
    )
}

/** One lap of a population: its recording, its timed span, its identity and its run. */
data class PopulationLap(
    val session: TelemetrySession,
    val start: Double,
    val end: Double,
    val reference: Any?,
    /** The recording it comes from; a run's fastest lap stands for it in time-loss ranking. */
    val runId: String = "",
)

/** Sector times of a population on one shared axis, and their theoretical best. */
data class PopulationSectors(
    val axis: ProgressAxis,
    /** In population order. */
    val times: List<LapSectorTimes>,
    /** Each lap projected onto [axis], in population order. */
    val traces: List<List<ProgressSegment>>,
    val best: TheoreticalBestLap,
)

/**
 * Times [population] against [approved] on the axis built from lap [axisLapNumber] of
 * [axisLaps] (the lap the segments were approved on, usually the fastest), as Telemetry's
 * calculateOutingTheoreticalBest does. Null when that lap has no trace or the axis is invalid.
 */
fun timePopulation(
    axisLaps: LapSession,
    axisLapNumber: Int,
    approved: ApprovedSegments,
    population: List<PopulationLap>,
): PopulationSectors? {
    val gate = axisLaps.startGate ?: return null
    val trace = axisLaps.lapTraces.firstOrNull { it.lapNumber == axisLapNumber } ?: return null
    val axis = buildProgressAxis(trace, geoMidpoint(gate.endpointA, gate.endpointB), gate)
    if (!axis.valid) return null
    val traces = population.map { projectLapTrace(axis, it.session, it.start, it.end) }
    val times = population.mapIndexed { i, lap ->
        computeLapSectorTimes(approved, axis.lengthMeters, traces[i], lap.start, lap.end, lap.reference)
    }
    return PopulationSectors(axis, times, traces, computeTheoreticalBest(approved, times))
}
