@file:Suppress(
    "ReturnCount",
    "CyclomaticComplexMethod",
    "LongMethod",
    "ComplexCondition",
    "LongParameterList",
    "MagicNumber",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.SegmentProposalBoundary
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentProposal
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Port of FlappedEar Telemetry analysis/corner_phases.dart (Apache 2.0), from FlappedEar Overlays'
// CornerPhases: a corner's geometric entry, apex and exit on a progress axis, and one lap's
// minimum-speed location inside it. Entry, apex and exit are track geometry shared by every lap;
// the minimum-speed location is a per-lap measurement and is never used as the apex.

const val CornerPhaseAlgorithm = "corner-phase-v1"

const val CornerPhaseCurvatureOnset = "curvatureOnset"
const val CornerPhasePeakCurvature = "peakCurvatureRegion"
const val CornerPhaseCurvatureRelease = "curvatureRelease"
const val CornerPhaseMinimumSpeed = "minimumSpeed"

/** Unresolved reasons: the phase has no location. */
const val CornerPhaseMultipleApexes = "multipleApexes"
const val CornerPhaseSpeedChannelMissing = "speedChannelMissing"
const val CornerPhaseIncompleteCoverage = "incompleteCoverage"
const val CornerPhaseFlatSpeed = "flatSpeed"
const val CornerPhaseCrossesGate = "crossesGate"
const val CornerPhaseInsufficientGeometry = "insufficientGeometry"
const val CornerPhaseInvalidInput = "invalidInput"

/** Uncertainty reasons: located, but not a sharp single point. */
const val CornerPhaseBroadPeak = "broadPeak"
const val CornerPhaseAtCornerBoundary = "atCornerBoundary"

/** Most samples [locateMinimumSpeed] takes across one corner. */
const val MaximumMinimumSpeedSamples = 100000

/** The proposal algorithm entry and exit come from. */
const val TrackSegmentProposalAlgorithm = "track-segment-proposal-v2"

/** One phase of a corner. */
data class CornerPhasePoint(
    val method: String = "",
    val progressMeters: Double = 0.0,
    val toleranceMeters: Double = 0.0,
    val uncertaintyReasons: List<String> = emptyList(),
    /** Non-empty: no location is proposed. */
    val unresolvedReason: String = "",
    /** Method inputs and measured values, for review. */
    val evidence: Map<String, Any?> = emptyMap(),
) {
    val resolved: Boolean get() = unresolvedReason.isEmpty()
}

/** A corner's geometric phases. */
data class CornerGeometryPhases(
    val entry: CornerPhasePoint = CornerPhasePoint(),
    val apex: CornerPhasePoint = CornerPhasePoint(),
    val exit: CornerPhasePoint = CornerPhasePoint(),
    /** Every separate high-curvature region, in travel order; more than one leaves the apex unresolved. */
    val apexCandidatesMeters: List<Double> = emptyList(),
    val valid: Boolean = false,
)

/** Thresholds of [proposeCornerGeometryPhases] and [locateMinimumSpeed]. */
data class CornerPhaseOptions(
    /** Curvature at or above this fraction of the corner peak starts a region. */
    val apexRegionRatio: Double = 0.8,
    /** A region ends only once curvature falls below this fraction. */
    val apexSeparationRatio: Double = 0.6,
    /** Speeds within this fraction of the corner's speed range count as the minimum. */
    val flatSpeedFraction: Double = 0.02,
)

private fun inProgressRange(progress: Double, start: Double, end: Double) =
    if (start <= end) progress >= start && progress < end else progress >= start || progress < end

/** The indices of [features]' samples inside [corner], in travel order, across the gate too. */
fun cornerSampleIndices(features: TrackFeatures, corner: TrackSegmentProposal): List<Int> {
    val n = features.samples.size
    val start = corner.start.progressMeters
    val end = corner.end.progressMeters
    var first = 0
    for (i in 0 until n) {
        if (features.samples[i].progressMeters >= start) {
            first = i
            break
        }
    }
    val indices = mutableListOf<Int>()
    var index = first
    while (indices.size < n && inProgressRange(features.samples[index].progressMeters, start, end)) {
        indices += index
        index = (index + 1) % n
    }
    return indices
}

private fun validCorner(corner: TrackSegmentProposal, length: Double): Boolean {
    fun inAxis(value: Double) = value.isFinite() && value >= 0.0 && value <= length
    return corner.type == TrackSegmentType.Corner &&
        inAxis(corner.start.progressMeters) &&
        inAxis(corner.end.progressMeters) &&
        corner.start.progressMeters != corner.end.progressMeters
}

private fun validOptions(options: CornerPhaseOptions) =
    options.apexRegionRatio.isFinite() && options.apexSeparationRatio.isFinite() &&
        options.flatSpeedFraction.isFinite() && options.apexSeparationRatio > 0.0 &&
        options.apexSeparationRatio < options.apexRegionRatio && options.apexRegionRatio <= 1.0 &&
        options.flatSpeedFraction > 0.0 && options.flatSpeedFraction < 1.0

private fun boundaryPhase(method: String, boundary: SegmentProposalBoundary) = CornerPhasePoint(
    method = method,
    progressMeters = boundary.progressMeters,
    toleranceMeters = boundary.toleranceMeters,
    uncertaintyReasons = boundary.uncertaintyReasons.map { it.jsonName },
    evidence = mapOf("source" to TrackSegmentProposalAlgorithm),
)

/**
 * Entry and exit reuse the corner proposal's boundaries (and their uncertainty); the apex is the
 * midpoint of the corner's high-curvature region. [corner] must be a corner on this axis.
 */
fun proposeCornerGeometryPhases(
    axis: ProgressAxis,
    features: TrackFeatures,
    corner: TrackSegmentProposal,
    options: CornerPhaseOptions = CornerPhaseOptions(),
): CornerGeometryPhases {
    if (!axis.valid || !features.valid || features.samples.size != axis.points.size || axis.points.size < 4 ||
        !(axis.spacingMeters > 0.0) || !validCorner(corner, axis.lengthMeters) || !validOptions(options)
    ) {
        return CornerGeometryPhases()
    }
    val entry = boundaryPhase(CornerPhaseCurvatureOnset, corner.start)
    val exit = boundaryPhase(CornerPhaseCurvatureRelease, corner.end)
    fun result(apex: CornerPhasePoint, candidates: List<Double> = emptyList()) =
        CornerGeometryPhases(entry, apex, exit, candidates, valid = true)

    val indices = cornerSampleIndices(features, corner)
    val direction = if (corner.turnRadians != 0.0) corner.turnRadians else corner.peakCurvaturePerMeter
    val sign = if (direction >= 0.0) 1.0 else -1.0
    var peak = 0.0
    val turning = indices.map { index ->
        (sign * features.samples[index].curvaturePerMeter).also { peak = max(peak, it) }
    }
    if (indices.size < 3 || !(peak > 0.0) || direction == 0.0) {
        return result(CornerPhasePoint(CornerPhasePeakCurvature, unresolvedReason = CornerPhaseInsufficientGeometry))
    }

    // Hysteresis keeps curvature ripple on a constant-radius arc from splitting one region.
    val regions = mutableListOf<IntArray>()
    var open = false
    for (j in turning.indices) {
        if (!open && turning[j] >= options.apexRegionRatio * peak) {
            regions += intArrayOf(j, j)
            open = true
        } else if (open && turning[j] < options.apexSeparationRatio * peak) {
            open = false
        } else if (open && turning[j] >= options.apexRegionRatio * peak) {
            regions.last()[1] = j
        }
    }

    val spacing = axis.spacingMeters
    val length = axis.lengthMeters
    val baseTolerance = features.smoothingMeters + spacing
    fun regionStart(region: IntArray) = features.samples[indices[region[0]]].progressMeters
    fun regionMidpoint(region: IntArray) =
        (regionStart(region) + (region[1] - region[0]) * spacing / 2.0) % length
    val candidates = regions.map(::regionMidpoint)
    val evidence = linkedMapOf<String, Any?>(
        "peakCurvaturePerMeter" to sign * peak,
        "regionRatio" to options.apexRegionRatio,
        "separationRatio" to options.apexSeparationRatio,
        "smoothingMeters" to features.smoothingMeters,
        "candidatesMeters" to candidates,
    )
    if (regions.size != 1) {
        return result(
            CornerPhasePoint(
                CornerPhasePeakCurvature,
                unresolvedReason = CornerPhaseMultipleApexes,
                evidence = evidence,
            ),
            candidates,
        )
    }
    val region = regions.first()
    val halfWidth = (region[1] - region[0]) * spacing / 2.0
    evidence["regionStartMeters"] = regionStart(region)
    evidence["regionEndMeters"] = features.samples[indices[region[1]]].progressMeters
    val apex = CornerPhasePoint(
        method = CornerPhasePeakCurvature,
        progressMeters = regionMidpoint(region),
        toleranceMeters = max(baseTolerance, halfWidth + spacing),
        uncertaintyReasons = if (halfWidth > baseTolerance) listOf(CornerPhaseBroadPeak) else emptyList(),
        evidence = evidence,
    )
    return result(apex, candidates)
}

/**
 * One lap's `speed` every [stepMeters] across the corner, using its projected [lapTrace] on the
 * same axis. Any sample without coverage or a speed leaves the result unresolved.
 */
fun locateMinimumSpeed(
    axis: ProgressAxis,
    corner: TrackSegmentProposal,
    lapTrace: List<ProgressSegment>,
    session: TelemetrySession,
    stepMeters: Double,
    options: CornerPhaseOptions = CornerPhaseOptions(),
): CornerPhasePoint {
    val evidence = linkedMapOf<String, Any?>()
    fun unresolved(reason: String) =
        CornerPhasePoint(CornerPhaseMinimumSpeed, unresolvedReason = reason, evidence = evidence)
    if (!axis.valid || !validCorner(corner, axis.lengthMeters) || !validOptions(options) ||
        !stepMeters.isFinite() || stepMeters <= 0.0
    ) {
        return unresolved(CornerPhaseInvalidInput)
    }
    evidence["stepMeters"] = stepMeters
    val channelName = session.aliases[Aliases.SPEED].orEmpty()
    if (channelName.isEmpty() || session.channels[channelName] == null) {
        return unresolved(CornerPhaseSpeedChannelMissing)
    }
    evidence["channel"] = channelName
    evidence["unit"] = effectiveChannelUnit(session, channelName)
    // One lap is timed gate to gate, so the two halves of a gate-crossing corner are not one pass.
    if (corner.end.progressMeters < corner.start.progressMeters) return unresolved(CornerPhaseCrossesGate)

    val start = corner.start.progressMeters
    val span = corner.end.progressMeters - start
    val exact = ceil(span / stepMeters) + 1.0
    if (!(exact <= MaximumMinimumSpeedSamples)) return unresolved(CornerPhaseInvalidInput)
    val count = exact.toInt()
    val progress = DoubleArray(count)
    val values = DoubleArray(count)
    val times = DoubleArray(count)
    var missing = 0
    for (k in 0 until count) {
        progress[k] = min(start + k * stepMeters, corner.end.progressMeters)
        val time = timeAtProgress(lapTrace, progress[k])
        val value = time?.let { session.valueAt(channelName, it) }
        if (value == null) {
            ++missing
            continue
        }
        times[k] = time
        values[k] = value
    }
    evidence["samples"] = count
    if (missing > 0) {
        evidence["missingSamples"] = missing
        return unresolved(CornerPhaseIncompleteCoverage)
    }
    // The first smallest value, as std::minmax_element finds it.
    var lowest = 0
    var maximum = values[0]
    for (k in 1 until count) {
        if (values[k] < values[lowest]) lowest = k
        if (values[k] >= maximum) maximum = values[k]
    }
    val minimum = values[lowest]
    if (!(maximum - minimum > 1e-9 * max(1.0, abs(maximum)))) return unresolved(CornerPhaseFlatSpeed)
    val nearMinimum = minimum + options.flatSpeedFraction * (maximum - minimum)
    var left = lowest
    var right = lowest
    while (left > 0 && values[left - 1] <= nearMinimum) --left
    while (right + 1 < count && values[right + 1] <= nearMinimum) ++right
    evidence["minimumValue"] = minimum
    evidence["maximumValue"] = maximum
    evidence["telemetryTime"] = times[lowest]
    evidence["flatSpeedFraction"] = options.flatSpeedFraction
    return CornerPhasePoint(
        method = CornerPhaseMinimumSpeed,
        progressMeters = progress[lowest],
        toleranceMeters = stepMeters + max(progress[lowest] - progress[left], progress[right] - progress[lowest]),
        uncertaintyReasons = if (left == 0 || right == count - 1) listOf(CornerPhaseAtCornerBoundary) else emptyList(),
        evidence = evidence,
    )
}

/**
 * The geometric view of an approved [segment] on an axis: a corner proposal with the segment's
 * exact bounds and its turn and peak curvature measured from [features]. A straight-type proposal
 * on bad input.
 */
fun cornerFromSegment(axis: ProgressAxis, features: TrackFeatures, segment: TrackSegment): TrackSegmentProposal {
    val invalid = TrackSegmentProposal(
        TrackSegmentType.Straight,
        "",
        SegmentProposalBoundary(0.0, 0.0),
        SegmentProposalBoundary(0.0, 0.0),
    )
    val start = segment.startProgressMeters
    val end = segment.endProgressMeters
    val validSegment = segment.id.isNotEmpty() && segment.name.isNotEmpty() && start.isFinite() &&
        end.isFinite() && start >= 0.0 && end >= 0.0 && start != end
    if (!axis.valid || !features.valid || features.samples.size != axis.points.size || !validSegment) return invalid
    val length = axis.lengthMeters
    if (start > length || end > length) return invalid
    val bounds = TrackSegmentProposal(
        TrackSegmentType.Corner,
        segment.name,
        SegmentProposalBoundary(start, 0.0),
        SegmentProposalBoundary(end, 0.0),
    )
    var turn = 0.0
    var peak = 0.0
    for (index in cornerSampleIndices(features, bounds)) {
        val curvature = features.samples[index].curvaturePerMeter
        turn += curvature * axis.spacingMeters
        if (abs(curvature) > abs(peak)) peak = curvature
    }
    return bounds.copy(
        lengthMeters = if (end >= start) end - start else end + length - start,
        turnRadians = turn,
        peakCurvaturePerMeter = peak,
    )
}
