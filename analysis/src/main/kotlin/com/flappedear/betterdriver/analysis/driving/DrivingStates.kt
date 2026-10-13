package com.flappedear.betterdriver.analysis.driving

import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.corners.PedalScale
import com.flappedear.betterdriver.analysis.corners.brakingSourceQuality
import com.flappedear.betterdriver.analysis.corners.throttleScale
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG
import com.flappedear.betterdriver.analysis.data.declaredChannelUnit
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.optimal.AcceleratingAboveG
import com.flappedear.betterdriver.analysis.optimal.BrakingBelowG
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Driving states along the lap (FBBD-175), after FlappedEar Telemetry's driving-state analysis:
// at every position fix of a lap the car is braking, coasting (off both pedals), cornering (on a
// part throttle, or no pedal anyone can tell, with the car turning), accelerating or at full
// throttle. Each stretch between two fixes takes the state of the fix it starts at, so the time and
// distance in each state add up to the lap's covered time and distance exactly.
//
// The pedals are read from the recorded brake and throttle when the session's own checks trust
// them (the braking source and the throttle scale, as the braking point and the pickup read them).
// Without them the longitudinal acceleration stands in, and the states are inferred: a lift slows
// the car gently (between the braking threshold and -0.05 g), while a car holding its speed, at a
// part throttle or flat out at the top of a long straight, is taken as on the throttle. A car off
// the throttle and one on a part throttle through a bend both carry ~0 g along it, so with no
// throttle channel a turning car is never called coasting, only cornering: inferred coasting in a
// corner is a lower bound of the real one. How hard the car is turning is the lateral accelerometer
// when there is one, else the speed squared times the curvature of the track's line (calculated).
//
// Coaching question it answers: where on the lap, and in which corner, is the car neither braking
// nor driven — time spent coasting, which the driver can turn into braking later or the throttle
// sooner.

const val DrivingStatesAlgorithm = "driving-states-v1"

/** What the car is doing at one place on the lap. */
enum class DrivingState { Braking, Coasting, Cornering, Accelerating, FullThrottle }

/** Limitations of a lap's states. */
const val DrivingStatesNoLateral = "noLateralAcceleration"
const val DrivingStatesLateralCalculated = "lateralFromCurvature"

/** Unavailable reasons. */
const val DrivingStatesNoChannels = "noPedalOrAccelerationChannel"
const val DrivingStatesNoTrace = "noTrace"

/** Thresholds of [drivingStates]; pedal values in percent (a 0..1 pedal is read on the same scale). */
data class DrivingStateThresholds(
    /** A brake at or above this is braking. */
    val brakeOnPercent: Double = 10.0,
    /** A throttle at or above this is accelerating (the pickup's on threshold). */
    val throttleOnPercent: Double = 20.0,
    /** A throttle at or below this is off. */
    val throttleOffPercent: Double = 5.0,
    /** A throttle at or above this is full throttle. */
    val fullThrottlePercent: Double = 90.0,
    /** Inferred: decelerating harder than this, g, is braking (a lift decelerates ~0.1 g). */
    val brakingBelowG: Double = BrakingBelowG,
    /** Inferred: slowing between [brakingBelowG] and this, g, off a bend, is coasting. */
    val coastingBelowG: Double = CoastingBelowG,
    /** Inferred: in a bend, accelerating harder than this, g, is on the throttle rather than cornering. */
    val acceleratingAboveG: Double = AcceleratingAboveG,
    /** Turning at or above this, g, is cornering. */
    val corneringLateralG: Double = 0.30,
)

/** One stretch between two fixes of a lap, in one [state]. Metres along the session's axis. */
data class DrivingStateInterval(
    val startTime: Double,
    val endTime: Double,
    val startMeters: Double,
    val endMeters: Double,
    val state: DrivingState,
)

/** Consecutive stretches of one [state], for drawing. */
data class DrivingStateRun(val state: DrivingState, val startMeters: Double, val endMeters: Double)

/** Seconds and metres in each state over a range of the lap, and how much of the range was covered. */
data class DrivingStateTotals(
    val seconds: Map<DrivingState, Double>,
    val meters: Map<DrivingState, Double>,
    /** Metres of the range the lap has a state for. */
    val coveredMeters: Double,
    /** Metres in the range. */
    val spanMeters: Double,
) {
    fun seconds(state: DrivingState): Double = seconds[state] ?: 0.0

    fun meters(state: DrivingState): Double = meters[state] ?: 0.0

    /** Share of the range with a state, 0..1. */
    val coverage: Double get() = if (spanMeters > 0.0) coveredMeters / spanMeters else 0.0
}

/**
 * One lap's driving states: [intervals] in travel order, [provenance] `measured` when both pedals
 * were read from their channels, `inferred` when the acceleration stood in for either.
 */
data class LapDrivingStates(
    val lapReference: Any?,
    val intervals: List<DrivingStateInterval> = emptyList(),
    val provenance: String = "",
    val limitations: List<String> = emptyList(),
    val unavailableReason: String = "",
    /** The axis length, metres, for ranges across the start line. */
    val lengthMeters: Double = 0.0,
) {
    val valid: Boolean get() = unavailableReason.isEmpty() && intervals.isNotEmpty()

    /** Neighbouring stretches of one state joined, for a strip along the lap. */
    val runs: List<DrivingStateRun>
        get() = buildList {
            for (i in intervals) {
                val last = lastOrNull()
                if (last != null && last.state == i.state && abs(last.endMeters - i.startMeters) < JoinMeters) {
                    set(size - 1, last.copy(endMeters = i.endMeters))
                } else {
                    add(DrivingStateRun(i.state, i.startMeters, i.endMeters))
                }
            }
        }

    /** Over the whole lap. */
    fun totals(): DrivingStateTotals = totals(0.0, lengthMeters)

    /**
     * Over [fromMeters]..[toMeters] along the axis; a range with [toMeters] before [fromMeters]
     * crosses the start line. A stretch partly inside counts by the share of its metres inside.
     * Where two pieces of the lap's projection overlap (a piece after a GPS gap may start a little
     * behind where the one before ended), those metres count once.
     */
    fun totals(fromMeters: Double, toMeters: Double): DrivingStateTotals {
        val ranges = if (toMeters >= fromMeters) {
            listOf(fromMeters to toMeters)
        } else {
            listOf(fromMeters to lengthMeters, 0.0 to toMeters)
        }
        val seconds = mutableMapOf<DrivingState, Double>()
        val meters = mutableMapOf<DrivingState, Double>()
        var covered = 0.0
        val ordered = intervals.sortedBy { it.startMeters }
        for ((from, to) in ranges) {
            // Metres of the range before this are counted already.
            var reached = from
            for (i in ordered) {
                val span = i.endMeters - i.startMeters
                val start = max(reached, i.startMeters)
                val inside = min(to, i.endMeters) - start
                if (!(span > 0.0) || !(inside > 0.0)) continue
                val share = inside / span
                seconds.merge(i.state, (i.endTime - i.startTime) * share, Double::plus)
                meters.merge(i.state, inside, Double::plus)
                covered += inside
                reached = start + inside
            }
        }
        return DrivingStateTotals(seconds, meters, covered, ranges.sumOf { (a, b) -> b - a })
    }
}

/** Inferred: a lift slows the car by more than this, g (rolling resistance, drag and the engine). */
const val CoastingBelowG = -0.05

/** Stretches closer than this, metres, are one run. */
private const val JoinMeters = 0.5

private const val Percent = 100.0

/** How the pedals and the turning are read for one session. */
private class Readers(
    val brake: ((Double) -> Boolean?)?,
    val throttle: ((Double) -> Double?)?,
    val longitudinal: TelemetryChannel?,
    val lateral: TelemetryChannel?,
    val speed: TelemetryChannel?,
    val metresPerSecond: Double?,
)

/**
 * Whether the car is braking at a time, from the recorded brake the session's checks trust; null
 * without one. A brake in another unit than a pedal's (a pressure in bar or psi) is not read against
 * a pedal threshold.
 */
@Suppress("ReturnCount")
private fun brakeReader(session: TelemetrySession, t: DrivingStateThresholds): ((Double) -> Boolean?)? {
    val quality = brakingSourceQuality(session)
    if (!quality.brakeUsable) return null
    val unit = declaredChannelUnit(session, quality.brakeName).trim()
    if (unit.isNotEmpty() && unit != "%") return null
    val c = session.channels[quality.brakeName] ?: return null
    val brakeOn = if (quality.brakeScale == PedalScale.Fraction) t.brakeOnPercent / Percent else t.brakeOnPercent
    return { time: Double -> c.valueAt(time)?.let { it >= brakeOn } }
}

private fun readers(session: TelemetrySession, t: DrivingStateThresholds): Readers {
    val brake = brakeReader(session, t)
    val throttleName = session.aliases[Aliases.THROTTLE].orEmpty()
    val throttleChannel = session.channels[throttleName]
    val throttleUnit = if (throttleChannel == null) "" else declaredChannelUnit(session, throttleName).trim()
    val scale = if (throttleChannel == null) PedalScale.Unknown else throttleScale(session)
    val readable = scale != PedalScale.Unknown && (throttleUnit.isEmpty() || throttleUnit == "%")
    val throttle = throttleChannel?.takeIf { readable }
        ?.let { c ->
            val factor = if (scale == PedalScale.Fraction) Percent else 1.0
            { time: Double -> c.valueAt(time)?.let { it * factor } }
        }
    fun inG(alias: String) = accelerationAliasInG(session, alias)?.takeIf { it.supported }?.channel
    val longitudinal = inG(Aliases.LONGITUDINAL_ACCELERATION)
    val lateral = inG(Aliases.LATERAL_ACCELERATION)
    val speed = session.channel(Aliases.SPEED)
    val factor = speed?.let { metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, it.name)) }
    return Readers(brake, throttle, longitudinal, lateral, speed, factor)
}

/**
 * The driving states of one lap of [session], projected as [trace] onto an axis [lengthMeters]
 * long whose line has [features] (for the turning when there is no lateral accelerometer).
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
fun drivingStates(
    session: TelemetrySession,
    trace: List<ProgressSegment>,
    lengthMeters: Double,
    features: TrackFeatures?,
    lapReference: Any? = null,
    thresholds: DrivingStateThresholds = DrivingStateThresholds(),
): LapDrivingStates {
    val r = readers(session, thresholds)
    if (r.longitudinal == null && (r.brake == null || r.throttle == null)) {
        return LapDrivingStates(lapReference, unavailableReason = DrivingStatesNoChannels, lengthMeters = lengthMeters)
    }
    if (trace.all { it.samples.size < 2 }) {
        return LapDrivingStates(lapReference, unavailableReason = DrivingStatesNoTrace, lengthMeters = lengthMeters)
    }
    val curvature = features?.takeIf { it.valid && it.samples.isNotEmpty() }
    val lateralFromLine = r.lateral == null && curvature != null && r.speed != null && r.metresPerSecond != null
    fun lateralG(time: Double, meters: Double): Double? = when {
        r.lateral != null -> r.lateral.valueAt(time)?.let(::abs)
        lateralFromLine -> r.speed!!.valueAt(time)?.let { v ->
            val mps = v * r.metresPerSecond!!
            mps * mps * abs(curvatureAt(curvature!!, meters)) / StandardGravity
        }
        else -> null
    }
    fun state(time: Double, meters: Double): DrivingState? {
        // A trusted brake is read alone: where it has no value the state is not known.
        val braking = if (r.brake != null) {
            r.brake.invoke(time)
        } else {
            r.longitudinal?.valueAt(time)?.let { it < thresholds.brakingBelowG }
        } ?: return null
        if (braking) return DrivingState.Braking
        val throttle = r.throttle
        if (throttle != null) {
            val pedal = throttle(time) ?: return null
            return when {
                pedal >= thresholds.fullThrottlePercent -> DrivingState.FullThrottle
                pedal >= thresholds.throttleOnPercent -> DrivingState.Accelerating
                pedal <= thresholds.throttleOffPercent -> DrivingState.Coasting
                (lateralG(time, meters) ?: 0.0) >= thresholds.corneringLateralG -> DrivingState.Cornering
                else -> DrivingState.Accelerating
            }
        }
        val g = r.longitudinal!!.valueAt(time) ?: return null
        val turning = (lateralG(time, meters) ?: 0.0) >= thresholds.corneringLateralG
        return when {
            turning && g <= thresholds.acceleratingAboveG -> DrivingState.Cornering
            g < thresholds.coastingBelowG -> DrivingState.Coasting
            else -> DrivingState.Accelerating
        }
    }
    val intervals = mutableListOf<DrivingStateInterval>()
    for (segment in trace) {
        val samples = segment.samples
        for (k in 0 until samples.size - 1) {
            val a = samples[k]
            val b = samples[k + 1]
            val forward = b.telemetryTime > a.telemetryTime && b.progressMeters >= a.progressMeters
            val s = if (forward) state(a.telemetryTime, a.progressMeters) else null
            if (s != null) {
                intervals +=
                    DrivingStateInterval(a.telemetryTime, b.telemetryTime, a.progressMeters, b.progressMeters, s)
            }
        }
    }
    val measured = r.brake != null && r.throttle != null
    val limitations = buildList {
        if (r.lateral == null && !lateralFromLine) add(DrivingStatesNoLateral)
        if (lateralFromLine) add(DrivingStatesLateralCalculated)
    }
    return LapDrivingStates(
        lapReference,
        intervals,
        if (measured) BrakingProvenanceMeasured else BrakingProvenanceInferred,
        limitations,
        lengthMeters = lengthMeters,
    )
}

/** The curvature of the line nearest [meters] along it. */
private fun curvatureAt(features: TrackFeatures, meters: Double): Double {
    val samples = features.samples
    var low = 0
    var high = samples.size - 1
    while (low < high) {
        val middle = (low + high) ushr 1
        if (samples[middle].progressMeters < meters) low = middle + 1 else high = middle
    }
    val before = (low - 1).coerceAtLeast(0)
    val nearest = if (abs(samples[before].progressMeters - meters) < abs(samples[low].progressMeters - meters)) {
        before
    } else {
        low
    }
    return samples[nearest].curvaturePerMeter
}
