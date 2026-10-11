@file:Suppress(
    "ReturnCount",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "LongParameterList",
    "MagicNumber",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationInG
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.upperBound
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.SegmentationStamp
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.math.max

// Port of FlappedEar Telemetry analysis/braking_metrics.dart (Apache 2.0), from FlappedEar
// Overlays' BrakingMetrics: braking for one approved segment on one lap. The search runs from
// `approachMeters` before the segment's start (clipped at the gate and at the end of the previous
// approved corner) to its end; the braking point is the first onset inside it. Deceleration is
// read only from the recorded longitudinal acceleration over the same episode.

const val BrakingMetricsAlgorithm = "braking-metrics-v2"

const val BrakingNoneDetected = "noBrakingDetected"
const val BrakingIncompleteCoverage = "incompleteCoverage"
const val BrakingSegmentCrossesGate = "crossesGate"
const val BrakingDecelerationChannelMissing = "decelerationChannelMissing"
const val BrakingApproachClipped = "approachClippedAtGate"
const val BrakingApproachClippedAtCorner = "approachClippedAtPreviousCorner"
const val BrakingMixedProvenance = "mixedProvenance"

private const val GateEpsilon = 1e-6

/** Options of [computeBrakingMetrics]. */
data class BrakingMetricsOptions(
    val approachMeters: Double = 200.0,
    val onset: BrakingOnsetOptions = BrakingOnsetOptions(),
)

/** The braking of one segment on one lap. */
data class BrakingMetrics(
    val segmentId: String = "",
    val intervalStartMeters: Double = 0.0,
    val intervalEndMeters: Double = 0.0,
    val entryMeters: Double = 0.0,
    /** No braking point at all. */
    val unavailableReason: String = "",
    /** `measuredBrake` or `inferredDeceleration`. */
    val method: String = "",
    /** `measured` or `inferred`. */
    val provenance: String = "",
    val channel: String = "",
    val thresholdUnit: String = "",
    val onThreshold: Double = 0.0,
    val brakingPointMeters: Double? = null,
    val brakingPointTime: Double? = null,
    /** Entry minus braking point; negative inside the segment. */
    val distanceBeforeEntryMeters: Double? = null,
    val brakingSeconds: Double? = null,
    /** Only with projected coverage to the episode end. */
    val brakingDistanceMeters: Double? = null,
    /** Onset uncertainty, clipped approach. */
    val limitations: List<String> = emptyList(),
    /** Deceleration over the episode (positive) in g; [decelerationUnit] empty when undeclared. */
    val decelerationChannel: String = "",
    val decelerationUnit: String = "",
    val peakDeceleration: Double? = null,
    val meanDeceleration: Double? = null,
    val decelerationUnavailableReason: String = "",
    val stamp: SegmentationStamp = SegmentationStamp(),
    val valid: Boolean = false,
)

/**
 * The braking of [segmentId] on one lap from [lapStartTime] to [lapEndTime], projected as
 * [lapTrace] on an axis of [axisLengthMeters]. An interval bound on the gate takes the lap's
 * timed start or end.
 */
fun computeBrakingMetrics(
    axisLengthMeters: Double,
    approved: ApprovedSegments,
    segmentId: String,
    lapTrace: List<ProgressSegment>,
    session: TelemetrySession,
    lapStartTime: Double?,
    lapEndTime: Double?,
    options: BrakingMetricsOptions = BrakingMetricsOptions(),
): BrakingMetrics {
    if (!axisLengthMeters.isFinite() || axisLengthMeters <= 0.0 ||
        !options.approachMeters.isFinite() || options.approachMeters < 0.0
    ) {
        return BrakingMetrics()
    }
    val segment = approvedSegmentById(approved, segmentId) ?: return BrakingMetrics()
    val start = segment.startProgressMeters
    val end = segment.endProgressMeters
    var result = BrakingMetrics(
        segmentId = segmentId,
        entryMeters = start,
        stamp = cornerStamp(approved, BrakingMetricsAlgorithm),
        valid = true,
    )
    if (end < start) return result.copy(unavailableReason = BrakingSegmentCrossesGate)
    // One gate-to-gate lap cannot see an approach that began before the gate.
    var intervalStart = max(0.0, start - options.approachMeters)
    val limitations = mutableListOf<String>()
    if (start - options.approachMeters < 0.0) limitations += BrakingApproachClipped
    // The approach never reaches back into the previous approved corner.
    var clippedByCorner = false
    for (other in approved.segments) {
        if (other.id == segmentId || other.type != TrackSegmentType.Corner) continue
        val otherEnd = other.endProgressMeters
        if (otherEnd <= start + 1e-6 && otherEnd > intervalStart) {
            intervalStart = otherEnd
            clippedByCorner = true
        }
    }
    if (clippedByCorner) limitations += BrakingApproachClippedAtCorner
    result = result.copy(intervalStartMeters = intervalStart, intervalEndMeters = end, limitations = limitations)

    fun hasChannel(alias: String): Boolean {
        val name = session.aliases[alias].orEmpty()
        return name.isNotEmpty() && session.channels.containsKey(name)
    }
    if (!hasChannel(Aliases.BRAKE) && !hasChannel(Aliases.LONGITUDINAL_ACCELERATION)) {
        return result.copy(unavailableReason = BrakingNoChannel)
    }
    val fromTime = if (intervalStart <= GateEpsilon && lapStartTime != null) {
        lapStartTime
    } else {
        timeAtProgress(lapTrace, intervalStart)
    }
    val toTime = if (end >= axisLengthMeters - GateEpsilon && lapEndTime != null) {
        lapEndTime
    } else {
        timeAtProgress(lapTrace, end)
    }
    if (fromTime == null || toTime == null || !(toTime > fromTime)) {
        return result.copy(unavailableReason = BrakingIncompleteCoverage)
    }
    val detection = detectBrakingOnsets(session, fromTime, toTime, options.onset, lapTrace)
    result = result.copy(
        method = detection.method,
        provenance = detection.provenance,
        channel = detection.channel,
        thresholdUnit = detection.threshold.unit,
        onThreshold = detection.threshold.on,
    )
    if (!detection.valid || detection.unresolvedReason.isNotEmpty()) {
        return result.copy(
            unavailableReason = if (detection.valid) detection.unresolvedReason else BrakingIncompleteCoverage,
        )
    }
    val candidate = detection.candidates.firstOrNull { it.progressMeters != null }
        ?: return result.copy(unavailableReason = BrakingNoneDetected)
    val onsetProgress = candidate.progressMeters!!
    val episodeEnd = candidate.telemetryTime + candidate.durationSeconds
    val endProgress = progressAtTime(lapTrace, episodeEnd)
    // Only a continuous projection between onset and episode end yields a distance.
    val continuous = lapTrace.any { piece ->
        piece.samples.isNotEmpty() && piece.samples.first().telemetryTime <= candidate.telemetryTime &&
            piece.samples.last().telemetryTime >= episodeEnd
    }
    result = result.copy(
        brakingPointMeters = onsetProgress,
        brakingPointTime = candidate.telemetryTime,
        distanceBeforeEntryMeters = start - onsetProgress,
        brakingSeconds = candidate.durationSeconds,
        limitations = limitations + candidate.uncertaintyReasons,
        brakingDistanceMeters = if (endProgress != null && continuous && endProgress >= onsetProgress) {
            endProgress - onsetProgress
        } else {
            null
        },
    )

    val decelerationName = session.aliases[Aliases.LONGITUDINAL_ACCELERATION].orEmpty()
    val view = if (decelerationName.isEmpty()) null else accelerationInG(session, decelerationName)
    view ?: return result.copy(decelerationUnavailableReason = BrakingDecelerationChannelMissing)
    val deceleration = view.channel
    result = result.copy(decelerationChannel = decelerationName, decelerationUnit = view.unit)
    if (!view.supported) return result.copy(decelerationUnavailableReason = BrakingUnitMismatch)
    val times = deceleration.timestamps
    val values = deceleration.values
    val gapThreshold = deceleration.gapThreshold()
    val first = lowerBound(times, candidate.telemetryTime)
    val last = upperBound(times, episodeEnd)
    var peak = 0.0
    var sum = 0.0
    var samples = 0
    var previousTime: Double? = null
    var gap = false
    for (index in first until last) {
        val value = values[index]
        val previous = previousTime
        if (!value.isFinite() || (previous != null && gapThreshold > 0.0 && times[index] - previous > gapThreshold)) {
            gap = true
            break
        }
        previousTime = times[index]
        val magnitude = -value // negative longitudinal G is braking
        peak = if (samples == 0) magnitude else max(peak, magnitude)
        sum += magnitude
        ++samples
    }
    // The episode must be covered from onset to end, not just somewhere inside.
    val reach = max(gapThreshold, 0.0)
    val lastTime = previousTime
    val reachesEnds = samples > 0 && first != times.size && times[first] - candidate.telemetryTime <= reach &&
        lastTime != null && episodeEnd - lastTime <= reach
    if (gap || samples < 2 || !reachesEnds) {
        return result.copy(decelerationUnavailableReason = BrakingIncompleteCoverage)
    }
    return result.copy(peakDeceleration = peak, meanDeceleration = sum / samples)
}

/**
 * A minus B for the same segment and revision. A braking-point delta is positive when A brakes
 * further along the lap (later). Different methods are never compared (`mixedProvenance`).
 */
data class BrakingComparison(
    val brakingPointDeltaMeters: Double? = null,
    val brakingSecondsDelta: Double? = null,
    val brakingDistanceDeltaMeters: Double? = null,
    val peakDecelerationDelta: Double? = null,
    val meanDecelerationDelta: Double? = null,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

/** [a] minus [b]. */
fun compareBrakingMetrics(a: BrakingMetrics, b: BrakingMetrics): BrakingComparison {
    if (!a.valid || !b.valid || !sameSegment(a.segmentId, b.segmentId, a.stamp, b.stamp)) {
        return BrakingComparison(unavailableReason = "differentSegmentOrRevision")
    }
    if (a.brakingPointMeters == null || b.brakingPointMeters == null) {
        return BrakingComparison(unavailableReason = a.unavailableReason.ifEmpty { b.unavailableReason }, valid = true)
    }
    if (a.method != b.method || a.provenance != b.provenance || a.channel != b.channel) {
        return BrakingComparison(unavailableReason = BrakingMixedProvenance, valid = true)
    }
    val sameDeceleration = a.decelerationChannel == b.decelerationChannel && a.decelerationUnit == b.decelerationUnit
    return BrakingComparison(
        brakingPointDeltaMeters = delta(a.brakingPointMeters, b.brakingPointMeters),
        brakingSecondsDelta = delta(a.brakingSeconds, b.brakingSeconds),
        brakingDistanceDeltaMeters = delta(a.brakingDistanceMeters, b.brakingDistanceMeters),
        peakDecelerationDelta = if (sameDeceleration) delta(a.peakDeceleration, b.peakDeceleration) else null,
        meanDecelerationDelta = if (sameDeceleration) delta(a.meanDeceleration, b.meanDeceleration) else null,
        valid = true,
    )
}
