@file:Suppress(
    "ReturnCount",
    "LongMethod",
    "ComplexCondition",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.data.upperBound
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.SegmentationStamp
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.projectedCoverageMeters

// Port of FlappedEar Telemetry analysis/corner_speeds.dart (Apache 2.0), from FlappedEar Overlays'
// CornerSpeeds: entry and exit speeds at a segment's boundaries, the speed at the geometric apex
// and, separately, the lap's minimum speed inside the segment.

const val CornerSpeedsAlgorithm = "corner-speeds-v1"

/** Speed samples further apart than this along the corner limit every value (`sparseSamples`). */
const val SparseSampleSpacingMeters = 10.0

const val CornerSpeedNotACorner = "notACorner"
const val CornerSpeedSparseSamples = "sparseSamples"
const val CornerSpeedMixedProvenance = "mixedProvenance"
const val CornerSpeedDifferentSegmentOrRevision = "differentSegmentOrRevision"

/** One speed of a corner. */
data class CornerSpeedValue(
    /** In the speed channel's unit. */
    val value: Double? = null,
    val progressMeters: Double = 0.0,
    val telemetryTime: Double? = null,
    /** Set when [value] is null. */
    val unavailableReason: String = "",
    /** Present values that are less certain. */
    val limitations: List<String> = emptyList(),
)

/** The speeds of one segment on one lap. */
data class CornerSpeeds(
    val segmentId: String = "",
    val name: String = "",
    val type: String = "",
    val channel: String = "",
    val unit: String = "",
    /** `measured` when read from the recorded speed channel, otherwise `unavailable`. */
    val provenance: String = "",
    val entry: CornerSpeedValue = CornerSpeedValue(),
    val apex: CornerSpeedValue = CornerSpeedValue(),
    val minimum: CornerSpeedValue = CornerSpeedValue(),
    val exit: CornerSpeedValue = CornerSpeedValue(),
    val lengthMeters: Double = 0.0,
    val coveredMeters: Double = 0.0,
    /** 0 when it could not be measured. */
    val meanSampleSpacingMeters: Double = 0.0,
    val stamp: SegmentationStamp = SegmentationStamp(),
    val valid: Boolean = false,
)

/** The stamp of a result computed from [approved] by [algorithm]. */
internal fun cornerStamp(approved: ApprovedSegments, algorithm: String) =
    SegmentationStamp(approved.revision, approved.trackConfigurationReference, algorithm)

/** The approved segment [segmentId] of [approved] (the last of that id), or null. */
fun approvedSegmentById(approved: ApprovedSegments, segmentId: String): TrackSegment? =
    approved.segments.lastOrNull { it.id == segmentId }

private fun unavailable(progress: Double, reason: String) =
    CornerSpeedValue(progressMeters = progress, unavailableReason = reason)

// The recorded speed at a progress value of this lap; never bridged across a gap.
private fun speedAt(
    lap: List<ProgressSegment>,
    session: TelemetrySession,
    channel: String,
    progress: Double,
): CornerSpeedValue {
    val time = timeAtProgress(lap, progress) ?: return unavailable(progress, CornerPhaseIncompleteCoverage)
    val speed = session.valueAt(channel, time) ?: return unavailable(progress, CornerPhaseIncompleteCoverage)
    return CornerSpeedValue(speed, progress, time)
}

/**
 * The speeds of [segmentId] on one lap. [axis] and [features] are the shared axis the segments
 * were approved on and its features; [lapTrace] is the lap's projection on it.
 */
fun computeCornerSpeeds(
    axis: ProgressAxis,
    features: TrackFeatures,
    approved: ApprovedSegments,
    segmentId: String,
    lapTrace: List<ProgressSegment>,
    session: TelemetrySession,
): CornerSpeeds {
    if (!axis.valid || !features.valid) return CornerSpeeds()
    val segment = approvedSegmentById(approved, segmentId) ?: return CornerSpeeds()
    val length = axis.lengthMeters
    val start = segment.startProgressMeters
    val end = segment.endProgressMeters
    val type = segment.type.jsonName
    val base = CornerSpeeds(
        segmentId = segmentId,
        name = segment.name,
        type = type,
        lengthMeters = if (end >= start) end - start else end + length - start,
        stamp = cornerStamp(approved, CornerSpeedsAlgorithm),
        valid = true,
    )
    val channel = session.aliases[Aliases.SPEED].orEmpty()
    val speedChannel = session.channels[channel]
    if (channel.isEmpty() || speedChannel == null) {
        return base.copy(
            provenance = "unavailable",
            entry = unavailable(start, CornerPhaseSpeedChannelMissing),
            apex = unavailable(0.0, CornerPhaseSpeedChannelMissing),
            minimum = unavailable(0.0, CornerPhaseSpeedChannelMissing),
            exit = unavailable(end, CornerPhaseSpeedChannelMissing),
        )
    }
    val measured = base.copy(
        channel = channel,
        unit = effectiveChannelUnit(session, speedChannel.name),
        provenance = "measured",
    )
    if (end < start) {
        // The corner's two halves lie at opposite ends of a gate-to-gate lap.
        return measured.copy(
            coveredMeters = projectedCoverageMeters(lapTrace, start, length, length) +
                projectedCoverageMeters(lapTrace, 0.0, end, length),
            entry = unavailable(start, CornerPhaseCrossesGate),
            apex = unavailable(0.0, CornerPhaseCrossesGate),
            minimum = unavailable(0.0, CornerPhaseCrossesGate),
            exit = unavailable(end, CornerPhaseCrossesGate),
        )
    }
    val covered = projectedCoverageMeters(lapTrace, start, end, length)
    val entry = speedAt(lapTrace, session, channel, start)
    val exit = speedAt(lapTrace, session, channel, end)
    val corner = cornerFromSegment(axis, features, segment)
    val apex = if (segment.type != TrackSegmentType.Corner) {
        unavailable(0.0, CornerSpeedNotACorner)
    } else {
        val phases = proposeCornerGeometryPhases(axis, features, corner)
        when {
            !phases.valid -> unavailable(0.0, CornerPhaseInvalidInput)
            !phases.apex.resolved -> unavailable(0.0, phases.apex.unresolvedReason)
            else -> speedAt(lapTrace, session, channel, phases.apex.progressMeters).let {
                it.copy(limitations = it.limitations + phases.apex.uncertaintyReasons)
            }
        }
    }
    val located = locateMinimumSpeed(axis, corner, lapTrace, session, 1.0)
    val minimum = if (located.resolved) {
        CornerSpeedValue(
            value = located.evidence["minimumValue"] as Double,
            progressMeters = located.progressMeters,
            telemetryTime = located.evidence["telemetryTime"] as Double,
            limitations = located.uncertaintyReasons,
        )
    } else {
        unavailable(0.0, located.unresolvedReason)
    }

    // Sample density of the recorded channel between the boundary crossings.
    var spacing = 0.0
    var values = listOf(entry, apex, minimum, exit)
    val entryTime = entry.telemetryTime
    val exitTime = exit.telemetryTime
    if (entryTime != null && exitTime != null && exitTime > entryTime) {
        val times = speedChannel.timestamps
        val samples = upperBound(times, exitTime) - lowerBound(times, entryTime)
        val segmentLength = measured.lengthMeters
        spacing = if (samples >= 2) segmentLength / (samples - 1) else segmentLength
        if (spacing > SparseSampleSpacingMeters) {
            values = values.map {
                if (it.value != null) it.copy(limitations = it.limitations + CornerSpeedSparseSamples) else it
            }
        }
    }
    return measured.copy(
        coveredMeters = covered,
        entry = values[0],
        apex = values[1],
        minimum = values[2],
        exit = values[3],
        meanSampleSpacingMeters = spacing,
    )
}

/** A minus B for the same segment and approved revision; different channels or units are not compared. */
data class CornerSpeedsComparison(
    val entryDelta: Double? = null,
    val apexDelta: Double? = null,
    val minimumDelta: Double? = null,
    val exitDelta: Double? = null,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

internal fun delta(x: Double?, y: Double?) = if (x != null && y != null) x - y else null

internal fun sameSegment(a: String, b: String, stampA: SegmentationStamp, stampB: SegmentationStamp) =
    a == b && stampA.revision == stampB.revision &&
        stampA.trackConfigurationReference == stampB.trackConfigurationReference

/** [a] minus [b]. */
fun compareCornerSpeeds(a: CornerSpeeds, b: CornerSpeeds): CornerSpeedsComparison {
    if (!a.valid || !b.valid || !sameSegment(a.segmentId, b.segmentId, a.stamp, b.stamp)) {
        return CornerSpeedsComparison(unavailableReason = CornerSpeedDifferentSegmentOrRevision)
    }
    if (a.provenance != b.provenance || a.channel != b.channel || !sameSpeedUnit(a.unit, b.unit)) {
        return CornerSpeedsComparison(unavailableReason = CornerSpeedMixedProvenance)
    }
    return CornerSpeedsComparison(
        entryDelta = delta(a.entry.value, b.entry.value),
        apexDelta = delta(a.apex.value, b.apex.value),
        minimumDelta = delta(a.minimum.value, b.minimum.value),
        exitDelta = delta(a.exit.value, b.exit.value),
        valid = true,
    )
}
