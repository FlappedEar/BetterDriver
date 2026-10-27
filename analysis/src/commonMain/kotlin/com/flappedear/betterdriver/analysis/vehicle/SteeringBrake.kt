package com.flappedear.betterdriver.analysis.vehicle

import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The steering wheel over a session's laps, or a whole road drive (FBBD-322, UI-250 A): the most
 * lock each way in degrees (both positive) and the time the wheel was turned more than
 * [LargeLockDegrees] either way, summed over the [stretches] that had a wheel angle.
 */
data class SteeringStats(
    val mostLeftDegrees: Double,
    val mostRightDegrees: Double,
    val largeLockSeconds: Double,
    val stretches: Int,
)

/**
 * The brake pressure over a session's laps, or a whole road drive (FBBD-322, UI-251 A), in [unit]:
 * the hardest press, the typical stop (the median of each stop's highest pressure; null without a
 * stop), and the time at [HardBrakeShare] of full braking or more, summed over the [stretches] that
 * had a pressure.
 */
data class BrakePressureStats(
    val hardest: Double,
    val typicalStop: Double?,
    val hardSeconds: Double,
    val stretches: Int,
    val unit: String,
)

/** A wheel turned more than this either way counts as a lot of lock (UI-250 A, "Over 90°"). */
const val LargeLockDegrees = 90.0

/**
 * Hard braking: half of full braking. The FR-S map reads full braking (100 %) at byte 0x46 of frame
 * 0xD1, 89.6 bar at 1.28 bar a bit (FBBD-320), so half is 44.8 bar, the same moment its brake % reads 50.
 */
const val HardBrakeShare = 0.5
const val FullBrakeBar = 89.6

/** A stop starts at this pressure: below it the foot only rests on the pedal. */
const val BrakeStopBar = 5.0

/** A wait between two samples longer than this is a gap: it adds no time and ends a stop. */
private const val MaxSampleGapSeconds = 0.5

/** The steering wheel of [session] over [spans] (start and end seconds); null without a wheel angle there. */
fun steeringStats(session: TelemetrySession, spans: List<Pair<Double, Double>>): SteeringStats? {
    val c = vehicleChannel(session, VehicleChannel.SteeringAngle) ?: return null
    var left = 0.0
    var right = 0.0
    var seconds = 0.0
    var stretches = 0
    for ((from, to) in spans) {
        val samples = c.samplesIn(from, to)
        if (samples.isEmpty()) continue
        stretches++
        for (s in samples) {
            left = max(left, s.value)
            right = max(right, -s.value)
        }
        seconds += c.timeWhere(from, to) { abs(it) > LargeLockDegrees }
    }
    return if (stretches == 0) null else SteeringStats(left, right, seconds, stretches)
}

/**
 * The steering wheel of [session] from [from] to [to] seconds for a chart (UI-250 A): averages of
 * [stepSeconds] buckets, times from [from], a gap left blank; empty without a wheel angle there.
 */
fun steeringTrace(
    session: TelemetrySession,
    from: Double,
    to: Double,
    stepSeconds: Double = 0.2,
): List<List<SamplePoint>> {
    val c = vehicleChannel(session, VehicleChannel.SteeringAngle) ?: return emptyList()
    return bucketedSegments(c, from, to, stepSeconds, maxGapSeconds = 1.0)
        .map { segment -> segment.map { SamplePoint(it.time - from, it.value) } }
}

/** The brake pressure of [session] over [spans]; null without a pressure there, or in a unit other than bar. */
fun brakePressureStats(session: TelemetrySession, spans: List<Pair<Double, Double>>): BrakePressureStats? {
    val c = vehicleChannel(session, VehicleChannel.BrakePressure)
    // The unit read from the channel: a pressure in another unit is not shown.
    val unit = c?.unit?.trim()?.ifEmpty { "bar" }?.takeIf { it.equals("bar", ignoreCase = true) } ?: return null
    var hardest = 0.0
    var seconds = 0.0
    var stretches = 0
    // One list over all the spans, so a stop across the line between two laps counts once.
    val all = HashMap<Double, Double>()
    for ((from, to) in spans) {
        val samples = c.samplesIn(from, to)
        if (samples.isEmpty()) continue
        stretches++
        hardest = max(hardest, samples.maxOf { it.value })
        samples.forEach { all[it.time] = it.value }
        seconds += c.timeWhere(from, to) { it >= HardBrakeShare * FullBrakeBar }
    }
    val stops = stopPeaks(all.entries.sortedBy { it.key }.map { (t, v) -> SamplePoint(t, v) })
    return if (stretches == 0) null else BrakePressureStats(hardest, stops.sorted().median(), seconds, stretches, unit)
}

/**
 * The highest pressure of each stop in [samples], in time order: a stop runs while the pressure is
 * [BrakeStopBar] or more, and a gap in the samples ends it.
 */
private fun stopPeaks(samples: List<SamplePoint>): List<Double> {
    val peaks = mutableListOf<Double>()
    var peak = 0.0
    var last = Double.NaN
    for (s in samples) {
        if (peak > 0.0 && s.time - last > MaxSampleGapSeconds) {
            peaks += peak
            peak = 0.0
        }
        last = s.time
        if (s.value >= BrakeStopBar) {
            peak = max(peak, s.value)
        } else if (peak > 0.0) {
            peaks += peak
            peak = 0.0
        }
    }
    if (peak > 0.0) peaks += peak
    return peaks
}

/** The finite samples of this channel from [from] to [to] seconds, both included. */
private fun TelemetryChannel.samplesIn(from: Double, to: Double): List<SamplePoint> =
    timestamps.indices
        .filter { timestamps[it] in from..to && values[it].isFinite() }
        .map { SamplePoint(timestamps[it], values[it]) }

/**
 * The seconds from [from] to [to] this channel's value held where [test] is true: each sample counts
 * until the next one; a wait longer than [MaxSampleGapSeconds] is a gap and adds nothing.
 */
private fun TelemetryChannel.timeWhere(from: Double, to: Double, test: (Double) -> Boolean): Double {
    var seconds = 0.0
    for (i in 0 until timestamps.size - 1) {
        val t = timestamps[i]
        val counts = t >= from && t < to && values[i].isFinite()
        val gap = timestamps[i + 1] - t > MaxSampleGapSeconds
        if (counts && !gap && test(values[i])) seconds += (min(timestamps[i + 1], to) - t).coerceAtLeast(0.0)
    }
    return seconds
}

private fun List<Double>.median(): Double? = when {
    isEmpty() -> null
    size % 2 == 1 -> this[size / 2]
    else -> (this[size / 2 - 1] + this[size / 2]) / 2
}
