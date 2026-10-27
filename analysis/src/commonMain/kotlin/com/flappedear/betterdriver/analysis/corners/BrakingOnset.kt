@file:Suppress(
    "ReturnCount",
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationInG
import com.flappedear.betterdriver.analysis.data.declaredChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.upperBound
import com.flappedear.betterdriver.analysis.data.weakMemo
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import kotlin.math.max

// Port of FlappedEar Telemetry analysis/braking_source.dart and braking_onset.dart (Apache 2.0),
// from FlappedEar Overlays' BrakingOnset: which channel tells when the driver brakes (the measured
// brake when it is trustworthy, else the deceleration, by the quality of the data) and where
// braking episodes start inside a time window.

/** Fewer finite samples than this and a channel tells nothing. */
const val BrakingSourceMinimumSamples = 20

/** A deceleration is usable when it reaches this many g somewhere. */
const val BrakingSourceUsableG = 0.30

/** A hard braking reaches this many g and stays beyond [BrakingSourceUsableG] this long. */
const val BrakingSourceHardG = 0.45
const val BrakingSourceHardSeconds = 0.5

/** A brake in % (or undeclared) is pressed from this value; a 0..1 brake from the fraction. */
const val BrakingSourcePressedPercent = 10.0
const val BrakingSourcePressedFraction = 0.10

/** Hard brakings needed before the brake is judged, and the share it must be pressed in. */
const val BrakingSourceMinimumHardBrakings = 3
const val BrakingSourceMinimumAgreement = 0.5

/** What the session's brake and deceleration channels are worth. */
data class BrakingSourceQuality(
    val brakeName: String,
    val decelerationName: String,
    val brakeUsable: Boolean,
    val decelerationUsable: Boolean,
    val hardBrakings: Int,
    val hardBrakingsWithBrake: Int,
    val brakeScale: PedalScale = PedalScale.Percent,
) {
    val hasBrake: Boolean get() = brakeName.isNotEmpty()
    val hasDeceleration: Boolean get() = decelerationName.isNotEmpty()

    /** A recorded brake that gives way to a usable deceleration. */
    val brakeRejected: Boolean get() = hasBrake && !brakeUsable && decelerationUsable
}

private val qualities = weakMemo<TelemetrySession, BrakingSourceQuality>()

/** The quality of [session]'s brake and deceleration, judged over the whole recording. */
fun brakingSourceQuality(session: TelemetrySession): BrakingSourceQuality =
    qualities.getOrPut(session) { assessBrakingSource(session) }

private fun aliasName(session: TelemetrySession, alias: String): String {
    val name = session.aliases[alias].orEmpty()
    return if (name.isNotEmpty() && session.channels.containsKey(name)) name else ""
}

private fun finiteCount(channel: TelemetryChannel) = channel.values.count { it.isFinite() }

private fun assessBrakingSource(session: TelemetrySession): BrakingSourceQuality {
    val brakeName = aliasName(session, Aliases.BRAKE)
    val decelerationName = aliasName(session, Aliases.LONGITUDINAL_ACCELERATION)
    val brake = if (brakeName.isEmpty()) null else session.channels.getValue(brakeName)
    val view = if (decelerationName.isEmpty()) null else accelerationInG(session, decelerationName)
    val deceleration = view?.channel
    var decelerationUsable = false
    if (deceleration != null) {
        var lowest = Double.POSITIVE_INFINITY
        for (value in deceleration.values) if (value.isFinite() && value < lowest) lowest = value
        decelerationUsable = view.supported &&
            finiteCount(deceleration) >= BrakingSourceMinimumSamples &&
            -lowest >= BrakingSourceUsableG
    }
    val brakeUnit = if (brakeName.isEmpty()) "" else declaredChannelUnit(session, brakeName)
    val brakeHasData = brake != null && finiteCount(brake) >= BrakingSourceMinimumSamples
    val runs = if (decelerationUsable) {
        longitudinalRuns(deceleration!!, -1.0, BrakingSourceHardG, BrakingSourceUsableG, BrakingSourceHardSeconds)
    } else {
        emptyList()
    }
    val judged = brake != null && brakeHasData && (brakeUnit.isEmpty() || brakeUnit == "%")
    val ambiguous = judged && pedalScaleAmbiguous(brake!!, brakeUnit)
    val scale = when {
        !ambiguous -> PedalScale.Percent
        !decelerationUsable -> PedalScale.Unknown
        else -> judgedPedalScale(
            brake!!,
            runs,
            longitudinalRuns(deceleration!!, 1.0, ThrottleScalePeakG, ThrottleScaleHoldG, ThrottleScaleSeconds),
            BrakingSourcePressedFraction,
        )
    }
    val pressedOn = if (scale == PedalScale.Fraction) BrakingSourcePressedFraction else BrakingSourcePressedPercent
    var hard = 0
    var withBrake = 0
    if (judged) {
        hard = runs.size
        withBrake = runs.count { pedalPressedIn(brake!!, it, pressedOn) }
    }
    val brakeUsable = brake != null &&
        scale != PedalScale.Unknown &&
        brakeHasData &&
        (hard < BrakingSourceMinimumHardBrakings || withBrake >= BrakingSourceMinimumAgreement * hard)
    return BrakingSourceQuality(
        brakeName, decelerationName, brakeUsable, decelerationUsable, hard, withBrake, scale,
    )
}

const val BrakingOnsetAlgorithm = "braking-onset-v1"

const val BrakingMethodMeasured = "measuredBrake"
const val BrakingMethodInferred = "inferredDeceleration"
const val BrakingProvenanceMeasured = "measured"
const val BrakingProvenanceInferred = "inferred"

/** Unresolved reasons (no candidates). */
const val BrakingNoChannel = "noBrakeOrDecelerationChannel"
const val BrakingInferenceDisabled = "inferenceDisabled"
const val BrakingUnitMismatch = "unitMismatch"
const val BrakingNoSamples = "noSamplesInWindow"
const val BrakingScaleUnknown = "channelScaleUnknown"

/** Candidate uncertainty reasons. */
const val BrakingFollowsGap = "followsGap"
const val BrakingAlreadyActive = "alreadyBrakingAtWindowStart"
const val BrakingInterruptedByGap = "interruptedByGap"
const val BrakingTruncatedAtWindowEnd = "truncatedAtWindowEnd"
const val BrakingUnitUndeclared = "channelUnitUndeclared"
const val BrakingBrakeChannelNotUsed = "brakeChannelNotUsed"
const val BrakingScaleInferred = "channelScaleInferred"

/** Hysteresis thresholds on braking magnitude (negated longitudinal G for deceleration), in [unit]. */
data class BrakingThreshold(val on: Double, val off: Double, val unit: String)

/** Thresholds of [detectBrakingOnsets]. */
data class BrakingOnsetOptions(
    val measuredBrake: BrakingThreshold = BrakingThreshold(10.0, 5.0, "%"),
    val inferredDeceleration: BrakingThreshold = BrakingThreshold(0.30, 0.15, "g"),
    /** Shorter episodes are rejected as spikes. */
    val minimumDurationSeconds: Double = 0.2,
    val allowInferred: Boolean = true,
)

/** One braking episode's start. */
data class BrakingOnsetCandidate(
    val telemetryTime: Double,
    val toleranceSeconds: Double,
    val durationSeconds: Double,
    /** Channel value in its own sign convention. */
    val peakValue: Double,
    val progressMeters: Double? = null,
    val uncertaintyReasons: List<String> = emptyList(),
)

/** The braking episodes of a window. */
data class BrakingOnsetDetection(
    val method: String = "",
    val provenance: String = "",
    val channel: String = "",
    /** As declared by the source; empty when undeclared. */
    val channelUnit: String = "",
    val threshold: BrakingThreshold = BrakingThreshold(0.0, 0.0, ""),
    val minimumDurationSeconds: Double = 0.0,
    val candidates: List<BrakingOnsetCandidate> = emptyList(),
    val rejectedSpikes: Int = 0,
    val gaps: Int = 0,
    val unresolvedReason: String = "",
    val valid: Boolean = false,
)

private fun validThreshold(threshold: BrakingThreshold) =
    threshold.on.isFinite() && threshold.off.isFinite() && threshold.off > 0.0 &&
        threshold.on > threshold.off && threshold.unit.trim().isNotEmpty()

private class Episode(val onset: Double, val tolerance: Double, var peak: Double, var lastTime: Double) {
    val reasons = mutableListOf<String>()
}

/**
 * Braking episodes of [session] in [startTime]..[endTime]: the measured `brake` channel unless
 * [brakingSourceQuality] rejects it for the whole session, else `longitudinalAcceleration`
 * (inferred). Raw samples only; gaps are never bridged. [lapTrace], when given, maps onsets to
 * progress.
 */
fun detectBrakingOnsets(
    session: TelemetrySession,
    startTime: Double,
    endTime: Double,
    options: BrakingOnsetOptions = BrakingOnsetOptions(),
    lapTrace: List<ProgressSegment>? = null,
): BrakingOnsetDetection {
    if (!startTime.isFinite() || !endTime.isFinite() || endTime <= startTime ||
        !validThreshold(options.measuredBrake) || !validThreshold(options.inferredDeceleration) ||
        !options.minimumDurationSeconds.isFinite() || options.minimumDurationSeconds <= 0.0
    ) {
        return BrakingOnsetDetection()
    }
    var result = BrakingOnsetDetection(valid = true, minimumDurationSeconds = options.minimumDurationSeconds)
    val quality = brakingSourceQuality(session)
    val brakeRejected = quality.brakeRejected && options.allowInferred
    val hasBrake = quality.hasBrake && !brakeRejected
    var sign = 1.0
    val fraction = hasBrake && quality.brakeScale == PedalScale.Fraction
    when {
        hasBrake -> {
            val measured = options.measuredBrake
            result = result.copy(
                method = BrakingMethodMeasured,
                provenance = BrakingProvenanceMeasured,
                channel = quality.brakeName,
                threshold = if (fraction) BrakingThreshold(measured.on / 100.0, measured.off / 100.0, "") else measured,
            )
            if (quality.brakeScale == PedalScale.Unknown) {
                return result.copy(
                    channelUnit = declaredChannelUnit(session, quality.brakeName),
                    unresolvedReason = BrakingScaleUnknown,
                )
            }
        }
        quality.hasDeceleration -> {
            result = result.copy(
                method = BrakingMethodInferred,
                provenance = BrakingProvenanceInferred,
                channel = quality.decelerationName,
                threshold = options.inferredDeceleration,
            )
            sign = -1.0 // negative longitudinal G is braking
            if (!options.allowInferred) return result.copy(unresolvedReason = BrakingInferenceDisabled)
        }
        else -> return result.copy(unresolvedReason = BrakingNoChannel)
    }

    // A pedal is read in its own unit; a deceleration in g whichever unit it is declared in.
    val channel: TelemetryChannel
    val declaredUnit: String
    val unitReadable: Boolean
    if (hasBrake) {
        channel = session.channels.getValue(result.channel)
        declaredUnit = declaredChannelUnit(session, result.channel)
        unitReadable = declaredUnit.isEmpty() || declaredUnit.lowercase() == result.threshold.unit.trim().lowercase()
    } else {
        val view = accelerationInG(session, result.channel)!!
        channel = view.channel
        declaredUnit = view.declaredUnit
        unitReadable = view.supported
    }
    result = result.copy(channelUnit = declaredUnit)
    val unitDeclared = declaredUnit.isNotEmpty()
    if (!unitReadable) return result.copy(unresolvedReason = BrakingUnitMismatch)

    val threshold = result.threshold
    val gapThreshold = channel.gapThreshold()
    val times = channel.timestamps
    val begin = lowerBound(times, startTime)
    val end = upperBound(times, endTime)
    val candidates = mutableListOf<BrakingOnsetCandidate>()
    var rejectedSpikes = 0
    var gaps = 0
    var episode: Episode? = null
    var haveSample = false
    var finiteSamples = 0
    var previousContiguous = false
    var followsGap = false
    var previousTime = 0.0
    var previousMagnitude = 0.0

    fun close(endTimeValue: Double, truncation: String?) {
        val current = episode!!
        val duration = endTimeValue - current.onset
        if (duration < options.minimumDurationSeconds && truncation == null) {
            ++rejectedSpikes
        } else {
            val reasons = current.reasons.toMutableList()
            if (truncation != null) reasons += truncation
            if (!unitDeclared) reasons += BrakingUnitUndeclared
            if (brakeRejected) {
                reasons += if (quality.brakeScale == PedalScale.Unknown) {
                    BrakingScaleUnknown
                } else {
                    BrakingBrakeChannelNotUsed
                }
            }
            if (fraction) reasons += BrakingScaleInferred
            candidates += BrakingOnsetCandidate(
                telemetryTime = current.onset,
                toleranceSeconds = current.tolerance,
                durationSeconds = duration,
                peakValue = sign * current.peak,
                progressMeters = lapTrace?.let { progressAtTime(it, current.onset) },
                uncertaintyReasons = reasons,
            )
        }
        episode = null
    }

    for (index in begin until end) {
        val time = times[index]
        val raw = channel.values[index]
        val gapBefore = haveSample && gapThreshold > 0.0 && time - previousTime > gapThreshold
        if (!raw.isFinite() || gapBefore) {
            if (!followsGap) ++gaps
            if (episode != null) close(episode!!.lastTime, BrakingInterruptedByGap)
            followsGap = true
            previousContiguous = false
            if (!raw.isFinite()) {
                haveSample = false
                continue
            }
        }
        ++finiteSamples
        val magnitude = sign * raw
        val open = episode
        if (open == null && magnitude >= threshold.on) {
            episode = if (previousContiguous && previousMagnitude < threshold.on) {
                val part = (threshold.on - previousMagnitude) / (magnitude - previousMagnitude)
                Episode(previousTime + (time - previousTime) * part, time - previousTime, magnitude, time)
            } else {
                Episode(time, channel.baseIntervalSeconds, magnitude, time).also {
                    it.reasons += if (followsGap) BrakingFollowsGap else BrakingAlreadyActive
                }
            }
        } else if (open != null && magnitude < threshold.off) {
            close(time, null)
        } else if (open != null) {
            open.peak = max(open.peak, magnitude)
            open.lastTime = time
        }
        haveSample = true
        previousContiguous = true
        followsGap = false
        previousTime = time
        previousMagnitude = magnitude
    }
    if (episode != null) close(episode!!.lastTime, BrakingTruncatedAtWindowEnd)
    return result.copy(
        candidates = candidates,
        rejectedSpikes = rejectedSpikes,
        gaps = gaps,
        unresolvedReason = if (finiteSamples == 0) BrakingNoSamples else "",
    )
}
