package com.flappedear.betterdriver.analysis.vehicle

import com.flappedear.betterdriver.analysis.corners.PedalScale
import com.flappedear.betterdriver.analysis.corners.brakingSourceQuality
import com.flappedear.betterdriver.analysis.corners.throttleScale
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.declaredChannelUnit
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.driving.DrivingStateThresholds
import com.flappedear.betterdriver.analysis.laps.TimedLap

/**
 * How the pedals were used over a stretch of a session (FBBD-176, UI-158 C): the shares of the time
 * the car moved at [PedalMinimumSpeedKmh] or more spent on full throttle, part throttle, coasting
 * and braking, each 0..1 and together 1. Without a usable brake [braking] is null and [coasting]
 * holds all the time off the throttle, braking included. [seconds] is the moving time read.
 */
data class PedalShares(
    val full: Double,
    val part: Double,
    val coasting: Double,
    val braking: Double?,
    val seconds: Double,
)

/** The lowest and highest of one of the car's temperatures over a stretch (UI-159 B), in its unit. */
data class TemperatureRange(val kind: VehicleChannel, val min: Double, val max: Double, val unit: String)

/** The car's values over one stretch of a session: a lap, or a whole road drive. */
data class VehicleStretch(
    val pedals: PedalShares?,
    /** Only the temperatures the session recorded there, in [VehicleChannel.Temperatures] order. */
    val temperatures: List<TemperatureRange>,
)

/** Below this speed the car is not driven, so the pedals say nothing (a stop, the pit lane). */
const val PedalMinimumSpeedKmh = 20.0

/** How often the pedals are read over a stretch. */
const val PedalStepSeconds = 0.1

private const val Percent = 100.0
private const val KmhPerMetrePerSecond = 3.6

/** A bucket's point sits at its middle. */
private const val HalfBucket = 0.5

/** How the throttle and brake of one session are read, as percent; null where a pedal has no value. */
private class PedalReaders(
    val throttle: (Double) -> Double?,
    val braking: ((Double) -> Boolean?)?,
    val speedKmh: (Double) -> Double?,
)

@Suppress("ReturnCount", "CyclomaticComplexMethod") // No throttle, no readable scale, no speed.
private fun pedalReaders(session: TelemetrySession, t: DrivingStateThresholds): PedalReaders? {
    val throttleName = session.aliases[Aliases.THROTTLE].orEmpty()
    val throttle = session.channels[throttleName] ?: return null
    val throttleUnit = declaredChannelUnit(session, throttleName).trim()
    val scale = throttleScale(session)
    if (scale == PedalScale.Unknown || (throttleUnit.isNotEmpty() && throttleUnit != "%")) return null
    val throttleFactor = if (scale == PedalScale.Fraction) Percent else 1.0
    val speed = session.channel(Aliases.SPEED) ?: return null
    val mps = metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, speed.name)) ?: return null
    val quality = brakingSourceQuality(session)
    val brakeUnit = declaredChannelUnit(session, quality.brakeName).trim()
    val brake = session.channels[quality.brakeName]
        ?.takeIf { quality.brakeUsable && (brakeUnit.isEmpty() || brakeUnit == "%") }
    val brakeOn = if (quality.brakeScale == PedalScale.Fraction) t.brakeOnPercent / Percent else t.brakeOnPercent
    return PedalReaders(
        throttle = { time -> throttle.valueAt(time)?.let { it * throttleFactor } },
        braking = brake?.let { c -> { time: Double -> c.valueAt(time)?.let { it >= brakeOn } } },
        speedKmh = { time -> speed.valueAt(time)?.let { it * mps * KmhPerMetrePerSecond } },
    )
}

/**
 * The pedal shares of [session] from [from] to [to] seconds; null without a measured throttle the
 * analysis can read, or when the car never moved there. A moment where a pedal has no value is left
 * out, never counted as released.
 */
// Moments without a value are skipped.
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
fun pedalShares(
    session: TelemetrySession,
    from: Double,
    to: Double,
    thresholds: DrivingStateThresholds = DrivingStateThresholds(),
): PedalShares? {
    val r = pedalReaders(session, thresholds) ?: return null
    if (!from.isFinite() || !to.isFinite() || to <= from) return null
    var full = 0
    var part = 0
    var coast = 0
    var brake = 0
    val steps = ((to - from) / PedalStepSeconds).toInt()
    for (i in 0..steps) {
        val time = from + i * PedalStepSeconds
        val kmh = r.speedKmh(time) ?: continue
        if (kmh < PedalMinimumSpeedKmh) continue
        val braking = if (r.braking == null) false else r.braking.invoke(time) ?: continue
        val throttle = r.throttle(time) ?: continue
        when {
            braking -> brake++
            throttle >= thresholds.fullThrottlePercent -> full++
            throttle <= thresholds.throttleOffPercent -> coast++
            else -> part++
        }
    }
    val total = full + part + coast + brake
    if (total == 0) return null
    val n = total.toDouble()
    return PedalShares(
        full = full / n,
        part = part / n,
        coasting = coast / n,
        braking = if (r.braking == null) null else brake / n,
        seconds = total * PedalStepSeconds,
    )
}

/** The range of [kind] in [session] from [from] to [to] seconds, both included; null without a value there. */
fun temperatureRange(session: TelemetrySession, kind: VehicleChannel, from: Double, to: Double): TemperatureRange? {
    val c = vehicleChannel(session, kind) ?: return null
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    for (i in c.timestamps.indices) {
        val v = c.values[i]
        if (c.timestamps[i] !in from..to || !v.isFinite()) continue
        if (v < low) low = v
        if (v > high) high = v
    }
    return if (low > high) null else TemperatureRange(kind, low, high, c.unit.ifEmpty { "°C" })
}

/** The car's values of [session] from [from] to [to] seconds. */
fun vehicleStretch(session: TelemetrySession, from: Double, to: Double): VehicleStretch = VehicleStretch(
    pedals = pedalShares(session, from, to),
    temperatures = VehicleChannel.Temperatures.mapNotNull { temperatureRange(session, it, from, to) },
)

/** The car's values over each of [laps], in their order. */
fun lapVehicleStretches(session: TelemetrySession, laps: List<TimedLap>): List<VehicleStretch> =
    laps.map { vehicleStretch(session, it.startTelemetryTime, it.endTelemetryTime) }

/**
 * The car's values over all of [session] (a road drive without laps, UI-162 A), from its first to its
 * last sample of any channel; null when it recorded none of them.
 */
fun sessionVehicleStretch(session: TelemetrySession): VehicleStretch? {
    val times = session.channels.values.filter { it.sampleCount > 0 }
    if (times.isEmpty()) return null
    val stretch = vehicleStretch(session, times.minOf { it.timestamps.first() }, times.maxOf { it.timestamps.last() })
    return stretch.takeIf { it.pedals != null || it.temperatures.isNotEmpty() }
}

/**
 * [channel] from [from] to [to] seconds as the averages of [stepSeconds] buckets, for a chart over a
 * whole session (UI-160 A): only values [accept] takes; a bucket without any ends a segment, and so
 * does a wait between two accepted samples longer than [maxGapSeconds], so a gap is never drawn
 * across. Each point sits at the middle of its bucket.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun bucketedSegments(
    channel: TelemetryChannel,
    from: Double,
    to: Double,
    stepSeconds: Double,
    maxGapSeconds: Double,
    accept: (Double) -> Boolean = Double::isFinite,
): List<List<SamplePoint>> {
    require(stepSeconds > 0.0) { "stepSeconds must be positive" }
    val segments = mutableListOf<List<SamplePoint>>()
    var current = mutableListOf<SamplePoint>()
    var bucket = -1L
    var sum = 0.0
    var count = 0
    var lastTime = Double.NaN
    fun flush() {
        if (count > 0) current.add(SamplePoint(from + (bucket + HalfBucket) * stepSeconds, sum / count))
        sum = 0.0
        count = 0
    }
    fun cut() {
        if (current.isNotEmpty()) segments.add(current)
        current = mutableListOf()
    }
    for (i in channel.timestamps.indices) {
        val time = channel.timestamps[i]
        val v = channel.values[i]
        if (time < from || time > to || !accept(v)) continue
        val b = ((time - from) / stepSeconds).toLong()
        val gap = !lastTime.isNaN() && time - lastTime > maxGapSeconds
        if (b != bucket || gap) {
            flush()
            if (gap || (bucket >= 0 && b > bucket + 1)) cut()
            bucket = b
        }
        sum += v
        count++
        lastTime = time
    }
    flush()
    cut()
    return segments
}
