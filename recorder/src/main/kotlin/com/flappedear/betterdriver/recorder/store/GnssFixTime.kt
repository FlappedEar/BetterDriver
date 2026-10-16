package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The receiver's accelerometer channels that a GNSS set may carry with its fix: a RaceBox sends
 * GNSS, acceleration and rotation in one message.
 */
private val AccelNames = setOf("accel_x", "accel_y", "accel_z")

/** The estimate works on this grid: 25 Hz, the RaceBox's rate. */
private const val StepSeconds = 0.04

/** Longest wait looked for: well past the 0.8 s Arek's RaceBox Mini showed. */
private const val MaxLatencySteps = 37

/** Each side of a sample averaged, so road buzz does not count: 0.24 s. */
private const val SmoothHalfSteps = 6

/** A reading is not bridged over a gap longer than this (a lost link); the whole-second skip is bridged. */
private const val MaxBridgedGapSeconds = 0.25

/** Only moving counts: standing still, GNSS speed is noise. */
private const val MinMovingKmh = 15.0

/** Less driving than this gives no estimate. */
private const val MinMovingSeconds = 60.0

/**
 * Below this agreement at the best wait the accelerometer is not trusted to time the fix. Arek's
 * RaceBox Mini reads 0.71 forward (2026-10-16) and 0.43 through a turned mount (2026-10-13).
 */
private const val MinAgreement = 0.35

/** The best wait must beat no wait by at least this, or the fix is taken as on time. */
private const val MinGain = 0.02

/** Shorter waits are left alone: one sample at 25 Hz. */
private const val MinShiftMicros = 40_000L

private const val G = 9.80665
private const val KmhPerMps = 3.6
private const val MicrosPerSecond = 1_000_000.0

/**
 * How much older the GNSS fix in a receiver's sets is than the accelerometer readings in the same
 * sets, in microseconds; null when that cannot be told (no accelerometer with the fix, too little
 * driving, readings that do not agree) or when it is under one sample (FBBD-229).
 *
 * A RaceBox sends its latest fix with its current acceleration in one message, and the message is
 * stamped when it arrives. On Arek's 2026-10-16 drive the fix was about 0.8 s older than the
 * acceleration: the RaceBox accelerometer agreed with the phone's at 0 s and with RaceChrono's
 * second RaceBox, while the change of GNSS speed came 0.8 s after both felt it (and the 2026-10-13
 * drive showed the same). So every position and speed sat 0.8 s late against the video and G.
 *
 * Found as the wait at which each accelerometer axis agrees best with the acceleration from GNSS
 * speed (both averaged over [SmoothHalfSteps] either side, only while moving), the best axis counting.
 */
@Suppress("ReturnCount")
fun gnssFixAgeMicros(sets: List<SampleSet>, accelIds: Set<Int>): Long? {
    if (accelIds.isEmpty()) return null
    val speedId = GnssChannels.SPEED_KMH.id
    val withBoth = sets
        .filter { s -> s.values.any { it.channelId == speedId } && s.values.any { it.channelId in accelIds } }
        .sortedBy { it.tMicros }
    if (withBoth.size < 2) return null
    val times = DoubleArray(withBoth.size) { withBoth[it].tMicros / MicrosPerSecond }
    val speed = valuesOf(withBoth, speedId).map { it / KmhPerMps }.toDoubleArray()
    val start = times.first()
    val steps = ((times.last() - start) / StepSeconds).toInt() + 1
    val v = resample(times, speed, start, steps)
    val moving = BooleanArray(steps) { v[it].isFinite() && v[it] * KmhPerMps >= MinMovingKmh }
    if (moving.count { it } * StepSeconds < MinMovingSeconds) return null
    val gps = DoubleArray(steps) { i ->
        if (i == 0 || i == steps - 1) Double.NaN else (v[i + 1] - v[i - 1]) / (2 * StepSeconds) / G
    }.smoothed()
    val best = accelIds.mapNotNull { id ->
        val accel = resample(times, valuesOf(withBoth, id), start, steps).smoothed()
        val curve = DoubleArray(MaxLatencySteps + 1) { k -> abs(laggedCorrelation(gps, accel, k, moving)) }
        curve.indices.filter { curve[it].isFinite() }.maxByOrNull { curve[it] }?.let { k -> Lag(k, curve[k], curve[0]) }
    }.maxByOrNull { it.agreement } ?: return null
    if (best.agreement < MinAgreement) return null
    if (best.atZero.isFinite() && best.agreement - best.atZero < MinGain) return null
    val micros = (best.steps * StepSeconds * MicrosPerSecond).roundToLong()
    return micros.takeIf { it >= MinShiftMicros }
}

/** One axis's best wait in [steps], its agreement there, and its agreement at no wait. */
private class Lag(val steps: Int, val agreement: Double, val atZero: Double)

/**
 * This session with each receiver's GNSS values put back at the time of their fix ([gnssFixAgeMicros]
 * earlier), its other values (acceleration, rotation, input voltage) left where they arrived. A set
 * then splits in two; the sets are kept in time order. GNSS values that would land before the
 * session's start are dropped. Unchanged when the age is not known, and for an imported session:
 * another app's file may carry acceleration worked out from GPS or read from the phone, already
 * timed against its fixes, so its sets say nothing about a receiver's fix age.
 */
fun SessionData.withGnssAtFixTime(): SessionData {
    val accelIds = channels.values.filter { it.name in AccelNames }.map { it.id }.toSet()
    val age = (if (info.imported == null) gnssFixAgeMicros(sampleSets, accelIds) else null) ?: return this
    val gnssIds = channels.values
        .filter { it.id in GnssChannels.IDS || it.name.startsWith("gnss_") }
        .map { it.id }
        .toSet()
    val moved = ArrayList<SampleSet>(sampleSets.size * 2)
    for (s in sampleSets) {
        val gnss = s.values.filter { it.channelId in gnssIds }
        if (gnss.isEmpty() || s.values.none { it.channelId in accelIds }) {
            moved += s
            continue
        }
        val rest = s.values.filter { it.channelId !in gnssIds }
        if (rest.isNotEmpty()) moved += SampleSet(s.tMicros, rest)
        if (s.tMicros - age >= 0) moved += SampleSet(s.tMicros - age, gnss)
    }
    return copy(sampleSets = moved.sortedBy { it.tMicros })
}

private fun valuesOf(sets: List<SampleSet>, id: Int): DoubleArray = DoubleArray(sets.size) { i ->
    sets[i].values.lastOrNull { it.channelId == id }?.value ?: Double.NaN
}

/** [values] at [times] read every [StepSeconds] from [start], straight between samples; NaN across a gap. */
private fun resample(times: DoubleArray, values: DoubleArray, start: Double, steps: Int): DoubleArray {
    val out = DoubleArray(steps) { Double.NaN }
    var j = 0
    for (i in 0 until steps) {
        val t = start + i * StepSeconds
        while (j < times.size - 2 && times[j + 1] <= t) j++
        val t0 = times[j]
        val t1 = times[j + 1]
        if (t < t0 || t > t1 || t1 - t0 > MaxBridgedGapSeconds) continue
        val a = values[j]
        val b = values[j + 1]
        if (a.isFinite() && b.isFinite()) out[i] = if (t1 == t0) a else a + (t - t0) / (t1 - t0) * (b - a)
    }
    return out
}

/** Mean over [SmoothHalfSteps] either side, NaN unless more than half of them are finite. */
private fun DoubleArray.smoothed(): DoubleArray = DoubleArray(size) { i ->
    var sum = 0.0
    var n = 0
    for (k in maxOf(0, i - SmoothHalfSteps)..minOf(size - 1, i + SmoothHalfSteps)) {
        if (this[k].isFinite()) {
            sum += this[k]
            n++
        }
    }
    if (n > SmoothHalfSteps) sum / n else Double.NaN
}

/** Correlation of [gps] with [accel] read [lag] steps earlier, over the [moving] steps where both are known. */
@Suppress("ReturnCount")
private fun laggedCorrelation(gps: DoubleArray, accel: DoubleArray, lag: Int, moving: BooleanArray): Double {
    var n = 0
    var sg = 0.0
    var sa = 0.0
    for (i in lag until gps.size) if (moving[i] && gps[i].isFinite() && accel[i - lag].isFinite()) {
        n++
        sg += gps[i]
        sa += accel[i - lag]
    }
    if (n < MinPairs) return Double.NaN
    val mg = sg / n
    val ma = sa / n
    var cov = 0.0
    var vg = 0.0
    var va = 0.0
    for (i in lag until gps.size) if (moving[i] && gps[i].isFinite() && accel[i - lag].isFinite()) {
        val dg = gps[i] - mg
        val da = accel[i - lag] - ma
        cov += dg * da
        vg += dg * dg
        va += da * da
    }
    return if (vg > 0.0 && va > 0.0) cov / sqrt(vg * va) else Double.NaN
}

/** Fewest pairs a correlation is worked out from. */
private const val MinPairs = 50
