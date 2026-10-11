@file:Suppress(
    "ReturnCount",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "LongParameterList",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationInG
import com.flappedear.betterdriver.analysis.data.declaredChannelUnit
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.data.upperBound
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.SegmentationStamp
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.projectedCoverageMeters
import kotlin.math.abs

// Port of FlappedEar Telemetry analysis/exit_metrics.dart (Apache 2.0), from FlappedEar Overlays'
// ExitMetrics: throttle pickup inside a segment (the first sustained rise after a lift, from the
// recorded throttle, or a positive longitudinal G onset labelled inferred without one) and the
// downstream exit: speed at the end boundary, speed at the end of the following straight (or
// `followMeters` on), and the time over it. No cause is assigned to any difference.

const val ExitMetricsAlgorithm = "exit-metrics-v1"

const val PickupMethodMeasured = "measuredThrottle"
const val PickupMethodInferred = "inferredAcceleration"
const val IntervalFollowingStraight = "followingStraight"
const val IntervalFixedDistance = "fixedDistance"

const val ExitNoLift = "noLift"
const val ExitNoPickup = "noPickupDetected"
const val ExitNoChannel = "noThrottleOrAccelerationChannel"
const val ExitUnitMismatch = "unitMismatch"
const val ExitIncompleteCoverage = "incompleteCoverage"
const val ExitCrossesGate = "crossesGate"
const val ExitSpeedChannelMissing = "speedChannelMissing"
const val ExitFollowsGap = "followsGap"
const val ExitTruncated = "truncatedAtWindowEnd"
const val ExitUnitUndeclared = "channelUnitUndeclared"
const val ExitScaleUnknown = "channelScaleUnknown"
const val ExitScaleInferred = "channelScaleInferred"
const val ExitMixedProvenance = "mixedProvenance"

private const val BoundaryEpsilon = 1e-6

/** Hysteresis thresholds of a pickup, in [unit]. */
data class ExitThreshold(val on: Double, val off: Double, val unit: String)

/** Options of [computeExitMetrics]. */
data class ExitMetricsOptions(
    val throttle: ExitThreshold = ExitThreshold(20.0, 10.0, "%"),
    val acceleration: ExitThreshold = ExitThreshold(0.10, 0.05, "g"),
    val minimumDurationSeconds: Double = 0.2,
    val followMeters: Double = 200.0,
)

/** Where the driver picks up the throttle in a segment. */
data class ThrottlePickup(
    /** `measuredThrottle` or `inferredAcceleration`. */
    val method: String = "",
    /** `measured` or `inferred`. */
    val provenance: String = "",
    val channel: String = "",
    val unit: String = "",
    val threshold: ExitThreshold = ExitThreshold(0.0, 0.0, ""),
    val progressMeters: Double? = null,
    val telemetryTime: Double? = null,
    val unavailableReason: String = "",
    val limitations: List<String> = emptyList(),
)

/** The exit of one segment on one lap. */
data class ExitMetrics(
    val segmentId: String = "",
    val pickup: ThrottlePickup = ThrottlePickup(),
    /** `followingStraight` or `fixedDistance`. */
    val intervalSource: String = "",
    val intervalStartMeters: Double = 0.0,
    val intervalEndMeters: Double = 0.0,
    val speedChannel: String = "",
    val speedUnit: String = "",
    /** At the segment end boundary. */
    val exitSpeed: Double? = null,
    val intervalEndSpeed: Double? = null,
    /** Over the whole interval, only with continuous coverage. */
    val elapsedSeconds: Double? = null,
    val downstreamUnavailableReason: String = "",
    val stamp: SegmentationStamp = SegmentationStamp(),
    val valid: Boolean = false,
)

private fun validThreshold(threshold: ExitThreshold) =
    threshold.on.isFinite() && threshold.off.isFinite() && threshold.off >= 0.0 &&
        threshold.on > threshold.off && threshold.unit.trim().isNotEmpty()

private class Rise(val time: Double?, val limitations: List<String>, val reason: String)

// The first rise to `on` after the channel was at or below `off`, sustained at or above `off` for
// `minimumDuration`. Raw samples only; gaps are never bridged.
private fun firstSustainedRise(
    channel: TelemetryChannel,
    on: Double,
    off: Double,
    minimumDuration: Double,
    startTime: Double,
    endTime: Double,
): Rise {
    val times = channel.timestamps
    val values = channel.values
    val gapThreshold = channel.gapThreshold()
    val begin = lowerBound(times, startTime)
    val end = upperBound(times, endTime)
    var lifted = false
    var rising = false
    var followsGap = false
    var previousContiguous = false
    var haveSample = false
    var previousTime = 0.0
    var previousValue = 0.0
    var riseStart = 0.0
    var riseLimitations = mutableListOf<String>()
    var finite = 0
    for (index in begin until end) {
        val time = times[index]
        val value = values[index]
        val gapBefore = haveSample && gapThreshold > 0.0 && time - previousTime > gapThreshold
        if (!value.isFinite() || gapBefore) {
            if (rising && previousTime - riseStart >= minimumDuration) return Rise(riseStart, riseLimitations, "")
            rising = false
            followsGap = true
            previousContiguous = false
            if (!value.isFinite()) {
                haveSample = false
                continue
            }
        }
        ++finite
        if (!rising) {
            if (!lifted) {
                if (value <= off) lifted = true
            } else if (value >= on) {
                rising = true
                riseLimitations = mutableListOf()
                riseStart = if (previousContiguous && previousValue < on) {
                    previousTime + (time - previousTime) * (on - previousValue) / (value - previousValue)
                } else {
                    time
                }
                if (followsGap) riseLimitations += ExitFollowsGap
            }
        } else if (value < off) {
            if (time - riseStart >= minimumDuration) return Rise(riseStart, riseLimitations, "")
            rising = false // a brief spike, not a pickup
        }
        haveSample = true
        previousContiguous = true
        followsGap = false
        previousTime = time
        previousValue = value
    }
    if (rising) {
        if (previousTime - riseStart < minimumDuration) riseLimitations += ExitTruncated
        return Rise(riseStart, riseLimitations, "")
    }
    val reason = when {
        finite == 0 -> ExitIncompleteCoverage
        lifted -> ExitNoPickup
        else -> ExitNoLift
    }
    return Rise(null, emptyList(), reason)
}

private fun fractionThreshold(options: ExitMetricsOptions) =
    ExitThreshold(options.throttle.on / 100.0, options.throttle.off / 100.0, "")

/**
 * The measured throttle's first sustained pickup over [startTime]..[endTime] by time alone, as
 * [computeExitMetrics] reads the throttle. Its time, or why there is none; no progress is set.
 */
fun measuredThrottlePickup(
    session: TelemetrySession,
    startTime: Double,
    endTime: Double,
    options: ExitMetricsOptions = ExitMetricsOptions(),
): ThrottlePickup {
    val name = session.aliases[Aliases.THROTTLE].orEmpty()
    val channel = session.channels[name]
    if (name.isEmpty() || channel == null) return ThrottlePickup(unavailableReason = ExitNoChannel)
    val declaredUnit = declaredChannelUnit(session, name)
    val scale = throttleScale(session)
    var pickup = ThrottlePickup(
        method = PickupMethodMeasured,
        provenance = "measured",
        channel = name,
        unit = declaredUnit,
        threshold = if (scale == PedalScale.Fraction) fractionThreshold(options) else options.throttle,
    )
    val declared = declaredUnit.isNotEmpty()
    pickup = when {
        declared && declaredUnit.lowercase() != pickup.threshold.unit.trim().lowercase() ->
            pickup.copy(unavailableReason = ExitUnitMismatch)
        scale == PedalScale.Unknown -> pickup.copy(unavailableReason = ExitScaleUnknown)
        !startTime.isFinite() || !endTime.isFinite() || !(endTime > startTime) ->
            pickup.copy(unavailableReason = ExitIncompleteCoverage)
        else -> {
            val rise = firstSustainedRise(
                channel, pickup.threshold.on, pickup.threshold.off, options.minimumDurationSeconds, startTime, endTime,
            )
            if (rise.time == null) {
                pickup.copy(unavailableReason = rise.reason)
            } else {
                pickup.copy(telemetryTime = rise.time, limitations = pickupLimitations(rise, declared, scale))
            }
        }
    }
    return pickup
}

private fun pickupLimitations(rise: Rise, declared: Boolean, scale: PedalScale) = buildList {
    addAll(rise.limitations)
    if (!declared) add(ExitUnitUndeclared)
    if (scale == PedalScale.Fraction) add(ExitScaleInferred)
}

private fun pickupIn(
    session: TelemetrySession,
    lapTrace: List<ProgressSegment>,
    start: Double,
    end: Double,
    length: Double,
    lapEndTime: Double?,
    options: ExitMetricsOptions,
): ThrottlePickup {
    val throttleName = session.aliases[Aliases.THROTTLE].orEmpty()
    val accelerationName = session.aliases[Aliases.LONGITUDINAL_ACCELERATION].orEmpty()
    val hasThrottle = throttleName.isNotEmpty() && session.channels.containsKey(throttleName)
    val hasAcceleration = accelerationName.isNotEmpty() && session.channels.containsKey(accelerationName)
    if (!hasThrottle && !hasAcceleration) return ThrottlePickup(unavailableReason = ExitNoChannel)
    var pickup = ThrottlePickup(
        method = if (hasThrottle) PickupMethodMeasured else PickupMethodInferred,
        provenance = if (hasThrottle) "measured" else "inferred",
        channel = if (hasThrottle) throttleName else accelerationName,
        threshold = if (hasThrottle) options.throttle else options.acceleration,
    )
    // A throttle is read in its own unit, an acceleration in g whichever unit it is declared in.
    val channel: TelemetryChannel
    val unitReadable: Boolean
    if (hasThrottle) {
        channel = session.channels.getValue(pickup.channel)
        pickup = pickup.copy(unit = declaredChannelUnit(session, pickup.channel))
        unitReadable = pickup.unit.isEmpty() || pickup.unit.lowercase() == pickup.threshold.unit.trim().lowercase()
    } else {
        val view = accelerationInG(session, pickup.channel)!!
        channel = view.channel
        pickup = pickup.copy(unit = view.declaredUnit)
        unitReadable = view.supported
    }
    val declared = pickup.unit.isNotEmpty()
    val scale = if (hasThrottle) throttleScale(session) else PedalScale.Percent
    if (scale == PedalScale.Fraction) pickup = pickup.copy(threshold = fractionThreshold(options))
    val fromTime = timeAtProgress(lapTrace, start)
    // A segment ending at the gate ends at the lap's timed end; the projection never reaches it.
    val toTime = if (end >= length - BoundaryEpsilon && lapEndTime != null) {
        lapEndTime
    } else {
        timeAtProgress(lapTrace, end)
    }
    return when {
        !unitReadable -> pickup.copy(unavailableReason = ExitUnitMismatch)
        scale == PedalScale.Unknown -> pickup.copy(unavailableReason = ExitScaleUnknown)
        fromTime == null || toTime == null || !(toTime > fromTime) ->
            pickup.copy(unavailableReason = ExitIncompleteCoverage)
        else -> {
            val rise = firstSustainedRise(
                channel, pickup.threshold.on, pickup.threshold.off, options.minimumDurationSeconds, fromTime, toTime,
            )
            if (rise.time == null) {
                pickup.copy(unavailableReason = rise.reason)
            } else {
                val progress = progressAtTime(lapTrace, rise.time)
                pickup.copy(
                    telemetryTime = rise.time,
                    progressMeters = progress,
                    limitations = pickupLimitations(rise, declared, scale),
                    unavailableReason = if (progress == null) ExitIncompleteCoverage else "",
                )
            }
        }
    }
}

/**
 * The throttle pickup and exit of [segmentId] on one lap. [lapEndTime] is the lap's timed gate
 * crossing, used when the interval ends at the gate.
 */
fun computeExitMetrics(
    axisLengthMeters: Double,
    approved: ApprovedSegments,
    segmentId: String,
    lapTrace: List<ProgressSegment>,
    session: TelemetrySession,
    lapEndTime: Double? = null,
    options: ExitMetricsOptions = ExitMetricsOptions(),
): ExitMetrics {
    if (!axisLengthMeters.isFinite() || axisLengthMeters <= 0.0 || !validThreshold(options.throttle) ||
        !validThreshold(options.acceleration) || !options.minimumDurationSeconds.isFinite() ||
        options.minimumDurationSeconds <= 0.0 || !options.followMeters.isFinite() || options.followMeters <= 0.0
    ) {
        return ExitMetrics()
    }
    val segment = approvedSegmentById(approved, segmentId) ?: return ExitMetrics()
    val length = axisLengthMeters
    val start = segment.startProgressMeters
    val end = segment.endProgressMeters
    var result = ExitMetrics(segmentId = segmentId, stamp = cornerStamp(approved, ExitMetricsAlgorithm), valid = true)
    if (end < start) {
        return result.copy(
            pickup = ThrottlePickup(unavailableReason = ExitCrossesGate),
            downstreamUnavailableReason = ExitCrossesGate,
        )
    }
    result = result.copy(pickup = pickupIn(session, lapTrace, start, end, length, lapEndTime, options))

    // Downstream interval from the end boundary.
    var source = IntervalFixedDistance
    var intervalEnd = end + options.followMeters
    for (next in approved.segments) {
        if (next.type == TrackSegmentType.Straight && abs(next.startProgressMeters - end) <= BoundaryEpsilon &&
            next.endProgressMeters > end
        ) {
            source = IntervalFollowingStraight
            intervalEnd = next.endProgressMeters
        }
    }
    result = result.copy(intervalStartMeters = end, intervalSource = source, intervalEndMeters = intervalEnd)
    val speedName = session.aliases[Aliases.SPEED].orEmpty()
    if (speedName.isNotEmpty() && session.channels.containsKey(speedName)) {
        result = result.copy(
            speedChannel = speedName,
            speedUnit = effectiveChannelUnit(session, speedName),
            exitSpeed = timeAtProgress(lapTrace, end)?.let { session.valueAt(speedName, it) },
        )
    }
    // The straight continues into the next lap.
    if (intervalEnd > length + BoundaryEpsilon) return result.copy(downstreamUnavailableReason = ExitCrossesGate)
    val intervalLength = intervalEnd - end
    val startTime = timeAtProgress(lapTrace, end)
    val endTime = if (intervalEnd >= length - BoundaryEpsilon) lapEndTime else timeAtProgress(lapTrace, intervalEnd)
    if (projectedCoverageMeters(lapTrace, end, intervalEnd, length) < intervalLength - BoundaryEpsilon ||
        startTime == null || endTime == null
    ) {
        result = result.copy(downstreamUnavailableReason = ExitIncompleteCoverage)
    } else {
        result = result.copy(
            elapsedSeconds = endTime - startTime,
            intervalEndSpeed = if (result.speedChannel.isNotEmpty()) session.valueAt(speedName, endTime) else null,
        )
    }
    if (result.speedChannel.isEmpty() && result.downstreamUnavailableReason.isEmpty()) {
        result = result.copy(downstreamUnavailableReason = ExitSpeedChannelMissing)
    }
    return result
}

/** A minus B for the same segment, revision and interval; pickup delta positive when A picks up later. */
data class ExitComparison(
    val pickupDeltaMeters: Double? = null,
    val exitSpeedDelta: Double? = null,
    val intervalEndSpeedDelta: Double? = null,
    val elapsedSecondsDelta: Double? = null,
    val pickupUnavailableReason: String = "",
    val valid: Boolean = false,
)

/** [a] minus [b]. */
fun compareExitMetrics(a: ExitMetrics, b: ExitMetrics): ExitComparison {
    if (!a.valid || !b.valid || !sameSegment(a.segmentId, b.segmentId, a.stamp, b.stamp) ||
        a.intervalSource != b.intervalSource || a.intervalEndMeters != b.intervalEndMeters
    ) {
        return ExitComparison()
    }
    val sameSpeed = a.speedChannel == b.speedChannel && sameSpeedUnit(a.speedUnit, b.speedUnit)
    var pickupDelta: Double? = null
    var pickupReason = ""
    val pickupA = a.pickup.progressMeters
    val pickupB = b.pickup.progressMeters
    if (a.pickup.method != b.pickup.method || a.pickup.channel != b.pickup.channel) {
        pickupReason = ExitMixedProvenance
    } else if (pickupA == null || pickupB == null) {
        pickupReason = a.pickup.unavailableReason.ifEmpty { b.pickup.unavailableReason }
    } else {
        pickupDelta = pickupA - pickupB
    }
    return ExitComparison(
        pickupDeltaMeters = pickupDelta,
        exitSpeedDelta = if (sameSpeed) delta(a.exitSpeed, b.exitSpeed) else null,
        intervalEndSpeedDelta = if (sameSpeed) delta(a.intervalEndSpeed, b.intervalEndSpeed) else null,
        elapsedSecondsDelta = delta(a.elapsedSeconds, b.elapsedSeconds),
        pickupUnavailableReason = pickupReason,
        valid = true,
    )
}
