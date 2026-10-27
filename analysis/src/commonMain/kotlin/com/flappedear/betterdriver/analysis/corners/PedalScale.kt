@file:Suppress(
    "ComplexCondition",
    "MagicNumber",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG
import com.flappedear.betterdriver.analysis.data.declaredChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.weakMemo

// Port of FlappedEar Telemetry analysis/pedal_scale.dart (Apache 2.0): the scale of a pedal
// channel that declares no unit, 0..100 % or a 0..1 fraction. The fraction is taken only when the
// longitudinal G shows the pedal working at that scale; otherwise the scale is unknown, never
// guessed.

/** How a pedal channel's values are read. */
enum class PedalScale { Percent, Fraction, Unknown }

/** Values up to this (with no unit declared) could be either scale. */
const val PedalScaleFractionMaximum = 1.5

/** A channel varying less than this never moved. */
const val PedalScaleMinimumRange = 0.05

/** Fewer finite samples than this and a channel tells nothing. */
const val PedalScaleMinimumSamples = 20

/** Events in the longitudinal G needed to judge a scale. */
const val PedalScaleMinimumEvents = 3

/** The share of those events in which the pedal must agree with the fraction scale. */
const val PedalScaleMinimumAgreement = 0.5

/** The pedal may be pressed this long before the G builds. */
const val PedalScaleLeadSeconds = 0.5

/** A pedal is released over a run when at least this share of its samples there are below `on`. */
const val PedalScaleReleasedShare = 0.8

/** A hard acceleration: it reaches this many g and stays beyond [ThrottleScaleHoldG] for [ThrottleScaleSeconds]. */
const val ThrottleScalePeakG = 0.20
const val ThrottleScaleHoldG = 0.10
const val ThrottleScaleSeconds = 1.0

/** A throttle in 0..1 is pressed from this fraction. */
const val ThrottleScaleFractionOn = 0.20

/** A start and end time. */
data class TimeRun(val start: Double, val end: Double)

/** Whether [channel] declares no unit ([unit]) and stays within 0..1 while moving. */
fun pedalScaleAmbiguous(channel: TelemetryChannel, unit: String): Boolean {
    if (unit.trim().isNotEmpty()) return false
    var finite = 0
    var lowest = Double.POSITIVE_INFINITY
    var highest = Double.NEGATIVE_INFINITY
    for (value in channel.values) {
        if (!value.isFinite()) continue
        ++finite
        if (value < lowest) lowest = value
        if (value > highest) highest = value
    }
    return finite >= PedalScaleMinimumSamples &&
        highest <= PedalScaleFractionMaximum &&
        highest - lowest >= PedalScaleMinimumRange
}

/**
 * The runs of [acceleration] where `sign * value` reaches [peak] and stays at or beyond [hold]
 * for at least [seconds], without a gap.
 */
fun longitudinalRuns(
    acceleration: TelemetryChannel,
    sign: Double,
    peak: Double,
    hold: Double,
    seconds: Double,
): List<TimeRun> {
    val runs = mutableListOf<TimeRun>()
    val times = acceleration.timestamps
    val values = acceleration.values
    val gap = acceleration.gapThreshold()
    var index = 0
    while (index < values.size) {
        if (!(sign * values[index] >= peak)) {
            ++index
            continue
        }
        val start = times[index]
        var end = index + 1
        while (end < values.size && sign * values[end] >= hold && !(gap > 0.0 && times[end] - times[end - 1] > gap)) {
            ++end
        }
        val finish = times[end - 1]
        index = end
        if (finish - start >= seconds) runs += TimeRun(start, finish)
    }
    return runs
}

/** Whether [pedal] reaches [on] from [PedalScaleLeadSeconds] before to the end of [run]. */
fun pedalPressedIn(pedal: TelemetryChannel, run: TimeRun, on: Double): Boolean {
    val times = pedal.timestamps
    var index = lowerBound(times, run.start - PedalScaleLeadSeconds)
    while (index < times.size && times[index] <= run.end) {
        if (pedal.values[index] >= on) return true
        ++index
    }
    return false
}

/** Whether at least [PedalScaleReleasedShare] of [pedal]'s finite samples over [run] are below [on]. */
fun pedalReleasedIn(pedal: TelemetryChannel, run: TimeRun, on: Double): Boolean {
    val times = pedal.timestamps
    var count = 0
    var below = 0
    var index = lowerBound(times, run.start)
    while (index < times.size && times[index] <= run.end) {
        val value = pedal.values[index]
        if (value.isFinite()) {
            ++count
            if (value < on) ++below
        }
        ++index
    }
    return count > 0 && below >= PedalScaleReleasedShare * count
}

/**
 * The scale of an ambiguous pedal judged against the G: a fraction when it reaches [fractionOn]
 * in at least half of the [pressed] runs and stays below it in at least half of the [released]
 * ones, with enough of each; otherwise unknown.
 */
fun judgedPedalScale(
    pedal: TelemetryChannel,
    pressed: List<TimeRun>,
    released: List<TimeRun>,
    fractionOn: Double,
): PedalScale {
    if (pressed.size < PedalScaleMinimumEvents || released.size < PedalScaleMinimumEvents) return PedalScale.Unknown
    val pressedCount = pressed.count { pedalPressedIn(pedal, it, fractionOn) }
    val releasedCount = released.count { pedalReleasedIn(pedal, it, fractionOn) }
    return if (pressedCount >= PedalScaleMinimumAgreement * pressed.size &&
        releasedCount >= PedalScaleMinimumAgreement * released.size
    ) {
        PedalScale.Fraction
    } else {
        PedalScale.Unknown
    }
}

private val throttleScales = weakMemo<TelemetrySession, PedalScale>()

/** The scale of [session]'s `throttle` channel, judged over the whole recording. */
fun throttleScale(session: TelemetrySession): PedalScale = throttleScales.getOrPut(session) {
    val name = session.aliases[Aliases.THROTTLE].orEmpty()
    val throttle = session.channels[name]
    if (throttle == null || !pedalScaleAmbiguous(throttle, declaredChannelUnit(session, name))) {
        PedalScale.Percent
    } else {
        val view = accelerationAliasInG(session, Aliases.LONGITUDINAL_ACCELERATION)
        if (view == null || !view.supported) {
            PedalScale.Unknown
        } else {
            judgedPedalScale(
                throttle,
                longitudinalRuns(view.channel, 1.0, ThrottleScalePeakG, ThrottleScaleHoldG, ThrottleScaleSeconds),
                longitudinalRuns(view.channel, -1.0, 0.45, 0.30, 0.5),
                ThrottleScaleFractionOn,
            )
        }
    }
}
