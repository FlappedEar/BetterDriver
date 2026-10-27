@file:Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "ComplexCondition", "LongParameterList")

package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.progress.GateCoverageToleranceMeters
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Port of FlappedEar Telemetry analysis/sector_timing.dart (Apache 2.0), itself a port of
// FlappedEar Overlays' SectorTiming: sector times per lap from the approved segments. Boundary
// crossings are interpolated on the lap's projection onto the shared axis; the gate boundaries
// (progress 0 and the axis length) use the lap's own timed start and end.

/** The algorithm a [LapSectorTimes] result is stamped with. */
const val SectorTimingAlgorithm = "sector-timing-v1"

/** Why a sector has no time: a GPS or projection gap inside it or at a boundary. */
const val SectorIncompleteCoverage = "incompleteCoverage"

/** Why two laps' sectors cannot be compared. */
const val SectorTimeDifferentSegmentOrRevision = "differentSegmentOrRevision"
const val SectorTimeSegmentNotFound = "segmentNotFound"

private const val BoundaryEpsilon = 1e-6

/** Which segments, and by which calculation, a result was computed from. */
data class SegmentationStamp(
    val revision: String = "",
    val trackConfigurationReference: String = "",
    val calculationAlgorithm: String = "",
)

/** One approved segment timed on one lap. */
data class SectorTime(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    val startProgressMeters: Double,
    /** Below [startProgressMeters] when the segment crosses the gate. */
    val endProgressMeters: Double,
    val lengthMeters: Double = 0.0,
    /** Projected coverage of the lap inside the sector. */
    val coveredMeters: Double = 0.0,
    /** Telemetry time at the start boundary. */
    val startTime: Double? = null,
    /** Telemetry time at the end boundary. */
    val endTime: Double? = null,
    /** Only when the whole sector is covered. */
    val seconds: Double? = null,
    /** Set when [seconds] is null. */
    val unavailableReason: String = "",
)

/** Every approved segment timed on one lap. [lapReference] is the lap's identity. */
data class LapSectorTimes(
    val lapReference: Any?,
    val stamp: SegmentationStamp = SegmentationStamp(),
    /** In approved order. */
    val sectors: List<SectorTime> = emptyList(),
    /** True when the approved segments tile the whole lap without gaps. */
    val completePartition: Boolean = false,
    val lapSeconds: Double = 0.0,
    /** With a complete partition and every sector timed. */
    val sumSeconds: Double? = null,
    val partitionErrorSeconds: Double? = null,
    val valid: Boolean = false,
) {
    fun sector(segmentId: String): SectorTime? = sectors.firstOrNull { it.segmentId == segmentId }
}

internal fun stampOf(approved: ApprovedSegments, algorithm: String) =
    SegmentationStamp(approved.revision, approved.trackConfigurationReference, algorithm)

private data class Range(val start: Double, val end: Double)

// Progress ranges the lap's projection covers, merged, with the ends snapped to the gate when
// they stop within the gate tolerance of it.
private fun coverage(lap: List<ProgressSegment>, length: Double): List<Range> {
    val ranges = lap.filter { it.samples.isNotEmpty() }
        .map { Range(it.samples.first().progressMeters, it.samples.last().progressMeters) }
        .sortedBy { it.start }
    val merged = mutableListOf<Range>()
    for (range in ranges) {
        if (merged.isNotEmpty() && range.start <= merged.last().end + BoundaryEpsilon) {
            merged[merged.size - 1] = Range(merged.last().start, max(merged.last().end, range.end))
        } else {
            merged += range
        }
    }
    if (merged.isNotEmpty()) {
        if (merged.first().start <= GateCoverageToleranceMeters) merged[0] = Range(0.0, merged.first().end)
        if (merged.last().end >= length - GateCoverageToleranceMeters) {
            merged[merged.size - 1] = Range(merged.last().start, length)
        }
    }
    return merged
}

private fun coveredWithin(ranges: List<Range>, from: Double, to: Double): Double {
    var covered = 0.0
    for (range in ranges) covered += max(0.0, min(range.end, to) - max(range.start, from))
    return covered
}

private fun fullyCovered(ranges: List<Range>, from: Double, to: Double) =
    ranges.any { it.start <= from + BoundaryEpsilon && it.end >= to - BoundaryEpsilon }

private fun crossingTime(
    lap: List<ProgressSegment>,
    progress: Double,
    length: Double,
    lapStartTime: Double,
    lapEndTime: Double,
): Double? {
    if (progress <= BoundaryEpsilon) return lapStartTime
    if (progress >= length - BoundaryEpsilon) return lapEndTime
    return timeAtProgress(lap, progress)
}

/**
 * The time of every segment of [approved] on one lap: [lapTrace] is the lap's projection onto the
 * axis (of [axisLengthMeters]) the segments were approved on, timed from [lapStartTime] to
 * [lapEndTime]. A segment crossing the gate is timed within the lap, as its part after its start
 * plus its part before its end.
 */
fun computeLapSectorTimes(
    approved: ApprovedSegments,
    axisLengthMeters: Double,
    lapTrace: List<ProgressSegment>,
    lapStartTime: Double,
    lapEndTime: Double,
    lapReference: Any?,
): LapSectorTimes {
    if (!axisLengthMeters.isFinite() || axisLengthMeters <= 0.0 ||
        !lapStartTime.isFinite() || !lapEndTime.isFinite() || lapEndTime <= lapStartTime
    ) {
        return LapSectorTimes(lapReference)
    }
    val length = axisLengthMeters
    val ranges = coverage(lapTrace, length)
    val lapSeconds = lapEndTime - lapStartTime

    var allTimed = approved.segments.isNotEmpty()
    var sum = 0.0
    val tiles = mutableListOf<Range>() // every segment's extent on [0, length], split at the gate
    val sectors = mutableListOf<SectorTime>()
    for (segment in approved.segments) {
        val start = segment.startProgressMeters
        val end = segment.endProgressMeters
        val startTime = crossingTime(lapTrace, start, length, lapStartTime, lapEndTime)
        val endTime = crossingTime(lapTrace, end, length, lapStartTime, lapEndTime)
        val sectorLength: Double
        val covered: Double
        var seconds: Double? = null
        if (end < start) {
            sectorLength = end + length - start
            covered = coveredWithin(ranges, start, length) + coveredWithin(ranges, 0.0, end)
            tiles += Range(start, length)
            tiles += Range(0.0, end)
            if (fullyCovered(ranges, start, length) &&
                fullyCovered(ranges, 0.0, end) &&
                startTime != null &&
                endTime != null &&
                lapEndTime - startTime > 0.0 &&
                endTime - lapStartTime > 0.0
            ) {
                seconds = (lapEndTime - startTime) + (endTime - lapStartTime)
            }
        } else {
            sectorLength = end - start
            covered = coveredWithin(ranges, start, end)
            tiles += Range(start, end)
            if (fullyCovered(ranges, start, end) && startTime != null && endTime != null && endTime > startTime) {
                seconds = endTime - startTime
            }
        }
        if (seconds != null) sum += seconds else allTimed = false
        sectors += SectorTime(
            segmentId = segment.id,
            name = segment.name,
            type = segment.type,
            startProgressMeters = start,
            endProgressMeters = end,
            lengthMeters = sectorLength,
            coveredMeters = covered,
            startTime = startTime,
            endTime = endTime,
            seconds = seconds,
            unavailableReason = if (seconds == null) SectorIncompleteCoverage else "",
        )
    }
    tiles.sortBy { it.start }
    var complete = tiles.isNotEmpty()
    var reached = 0.0
    for (tile in tiles) {
        if (abs(tile.start - reached) > BoundaryEpsilon) complete = false
        reached = tile.end
    }
    if (complete && abs(reached - length) > BoundaryEpsilon) complete = false
    return LapSectorTimes(
        lapReference = lapReference,
        stamp = stampOf(approved, SectorTimingAlgorithm),
        sectors = sectors,
        completePartition = complete,
        lapSeconds = lapSeconds,
        sumSeconds = if (complete && allTimed) sum else null,
        partitionErrorSeconds = if (complete && allTimed) abs(sum - lapSeconds) else null,
        valid = true,
    )
}

/** Metres of [fromMeters]..[toMeters] covered by the lap's projection, with sector timing's gate tolerance. */
fun projectedCoverageMeters(
    lapTrace: List<ProgressSegment>,
    fromMeters: Double,
    toMeters: Double,
    axisLengthMeters: Double,
): Double {
    if (!axisLengthMeters.isFinite() || axisLengthMeters <= 0.0 || !(toMeters >= fromMeters)) return 0.0
    return coveredWithin(coverage(lapTrace, axisLengthMeters), fromMeters, toMeters)
}

/** Lap A's time minus lap B's through one segment. */
data class SectorTimeComparison(
    val secondsDelta: Double? = null,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

/** A minus B for [segmentId], from two laps' results for the same approved revision. */
fun compareSectorTimes(a: LapSectorTimes, b: LapSectorTimes, segmentId: String): SectorTimeComparison {
    if (!a.valid || !b.valid ||
        a.stamp.revision != b.stamp.revision ||
        a.stamp.trackConfigurationReference != b.stamp.trackConfigurationReference
    ) {
        return SectorTimeComparison(unavailableReason = SectorTimeDifferentSegmentOrRevision)
    }
    val sectorA = a.sector(segmentId)
    val sectorB = b.sector(segmentId)
    if (sectorA == null || sectorB == null) return SectorTimeComparison(unavailableReason = SectorTimeSegmentNotFound)
    if (sectorA.seconds != null && sectorB.seconds != null) {
        return SectorTimeComparison(sectorA.seconds - sectorB.seconds, valid = true)
    }
    return SectorTimeComparison(
        unavailableReason = sectorA.unavailableReason.ifEmpty { sectorB.unavailableReason },
        valid = true,
    )
}
