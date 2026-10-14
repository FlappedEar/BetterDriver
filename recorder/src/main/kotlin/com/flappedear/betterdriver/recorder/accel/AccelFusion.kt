package com.flappedear.betterdriver.recorder.accel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One accelerometer's reading of one direction of the car (forward or left), already turned to the
 * car and levelled ([AxisDirection.correct]): [times] in seconds, strictly increasing, [values] in g
 * (NaN is no data). [residual] is its fit's ([AxisFit.residual]): how far from GPS it reads, in g².
 */
class DirectionReading(val name: String, val times: DoubleArray, val values: DoubleArray, val residual: Double) {
    init {
        require(times.size == values.size) { "$name has ${times.size} times and ${values.size} values" }
    }
}

/** What one accelerometer gave [fuseDirection]: its share of the average and how far its clock was moved. */
class FusionShare(val name: String, val weight: Double, val lagSeconds: Double)

/**
 * The car's acceleration along one direction from every accelerometer that agrees with GPS
 * ([fuseDirection]): [times] in seconds, [values] in g (NaN where none had a reading), and what each
 * accelerometer gave ([shares], the best first). [left] are those that read too differently from
 * the best one to be averaged with it.
 */
class FusedDirection(
    val times: DoubleArray,
    val values: DoubleArray,
    val shares: List<FusionShare>,
    val left: List<String> = emptyList(),
)

/** The fused reading's step: 25 Hz, the RaceBox's and the phone's rate. */
const val FusionStepSeconds = 0.04

/** How far one accelerometer's clock may be from the best one's: well past a BLE link's latency. */
const val MaxFusionLagSeconds = 0.3

/**
 * Below this agreement with the best accelerometer, another is not averaged with it. Each already
 * agrees with GPS at least [MinimumAxisCorrelation]; two accelerometers feeling the same car agree
 * with each other better than either does with GPS.
 */
const val MinimumFusionAgreement = 0.6

/** The most a reading is bridged between two samples: a RaceBox's whole-second skip is, a lost link is not. */
private const val MaxBridgedGapSeconds = 0.25

private const val LagStepSeconds = 0.01

/** The lag search compares the readings at about this step. */
private const val LagCompareStepSeconds = 0.1

/** ... each averaged over this either side, so buzz that two mounts feel differently does not count. */
private const val LagCompareHalfWindowSeconds = 0.1

/**
 * The car's acceleration along one direction from several accelerometers (FBBD-226), as RaceChrono
 * does with a RaceBox and the phone: each reading turned to the car and levelled, then averaged.
 *
 * The best reading (the least [DirectionReading.residual] against GPS) leads. Each other reading's
 * clock is first matched to it (the lag within [MaxFusionLagSeconds] where the two agree best: a
 * phone's sensor and a receiver over BLE are stamped tens of milliseconds apart, and averaging them
 * unmatched would blunt every braking point). A reading that then still agrees with the best one
 * less than [MinimumFusionAgreement] is a second opinion that does not hold up (a loose phone, a
 * holder that moves) and is left out. The rest are averaged at [FusionStepSeconds] steps, each
 * weighted by the inverse of its residual, so the steadier accelerometer counts for more; where one
 * has no reading (a RaceBox's whole-second skip, a gap in the link), the others carry on alone.
 *
 * One reading is returned as it is. Null without a reading.
 */
@Suppress("ReturnCount")
fun fuseDirection(readings: List<DirectionReading>): FusedDirection? {
    val usable = readings.filter { r -> r.times.size >= 2 && r.values.any { it.isFinite() } }
    if (usable.isEmpty()) return null
    val ranked = usable.sortedBy { it.residual.takeIf { r -> r.isFinite() } ?: Double.MAX_VALUE }
    val best = ranked.first()
    if (ranked.size == 1) return FusedDirection(best.times, best.values, listOf(FusionShare(best.name, 1.0, 0.0)))

    val lags = HashMap<DirectionReading, Double>()
    val left = ArrayList<String>()
    for (r in ranked.drop(1)) {
        val (lag, agreement) = bestLag(best, r)
        if (agreement >= MinimumFusionAgreement) lags[r] = lag else left += r.name
    }
    val members = listOf(best) + ranked.drop(1).filter { it in lags }
    if (members.size == 1) {
        return FusedDirection(best.times, best.values, listOf(FusionShare(best.name, 1.0, 0.0)), left)
    }
    val weights = weightsOf(members)
    val (times, values) = average(members, weights) { lags[it] ?: 0.0 }
    val shares = members.mapIndexed { k, m -> FusionShare(m.name, weights[k], lags[m] ?: 0.0) }
    return FusedDirection(times, values, shares, left)
}

/**
 * Each reading's share: the inverse of its residual, normalised. A residual of 0 (a simulated
 * reading) or none known counts as the smallest known.
 */
private fun weightsOf(members: List<DirectionReading>): List<Double> {
    fun known(r: DirectionReading) = r.residual.takeIf { it.isFinite() && it > 0.0 }
    val smallest = members.mapNotNull(::known).minOrNull() ?: 1.0
    val raw = members.map { 1.0 / (known(it) ?: smallest) }
    return raw.map { it / raw.sum() }
}

/** [members] averaged with [weights] at [FusionStepSeconds] steps, each read [lag] later than the step's time. */
private fun average(
    members: List<DirectionReading>,
    weights: List<Double>,
    lag: (DirectionReading) -> Double,
): Pair<DoubleArray, DoubleArray> {
    val start = members.minOf { it.times.first() - lag(it) }
    val end = members.maxOf { it.times.last() - lag(it) }
    val steps = floor((end - start) / FusionStepSeconds).toInt() + 1
    val times = DoubleArray(steps) { start + it * FusionStepSeconds }
    val values = DoubleArray(steps) { i ->
        var sum = 0.0
        var weight = 0.0
        for ((k, m) in members.withIndex()) {
            val v = m.at(times[i] + lag(m)) ?: continue
            sum += weights[k] * v
            weight += weights[k]
        }
        if (weight > 0.0) sum / weight else Double.NaN
    }
    return times to values
}

/**
 * The lag (seconds; positive when [other]'s stamps are late) at which [other] agrees best with
 * [best], and that agreement (correlation), compared at about [LagCompareStepSeconds] steps over the
 * time both have readings.
 */
private fun bestLag(best: DirectionReading, other: DirectionReading): Pair<Double, Double> {
    val from = maxOf(best.times.first(), other.times.first()) + MaxFusionLagSeconds
    val to = minOf(best.times.last(), other.times.last()) - MaxFusionLagSeconds
    if (to <= from) return 0.0 to Double.NaN
    val probes = ceil((to - from) / LagCompareStepSeconds).toInt() + 1
    val at = DoubleArray(probes) { from + it * LagCompareStepSeconds }
    val reference = DoubleArray(probes) { best.meanAround(at[it]) }
    val steps = (MaxFusionLagSeconds / LagStepSeconds).roundToInt()
    var bestLag = 0.0
    var bestAgreement = Double.NEGATIVE_INFINITY
    for (s in -steps..steps) {
        val lag = s * LagStepSeconds
        val c = correlation(reference, DoubleArray(probes) { other.meanAround(at[it] + lag) })
        // A tie keeps the smaller lag.
        if (c > bestAgreement || (c == bestAgreement && abs(lag) < abs(bestLag))) {
            bestAgreement = c
            bestLag = lag
        }
    }
    return bestLag to bestAgreement
}

/** Correlation of the pairs where both are finite; NaN with fewer than [MinimumFitPairs] or no spread. */
@Suppress("ReturnCount")
private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    var n = 0
    var sa = 0.0
    var sb = 0.0
    for (i in a.indices) if (a[i].isFinite() && b[i].isFinite()) {
        n++
        sa += a[i]
        sb += b[i]
    }
    if (n < MinimumFitPairs) return Double.NaN
    val ma = sa / n
    val mb = sb / n
    var cov = 0.0
    var va = 0.0
    var vb = 0.0
    for (i in a.indices) if (a[i].isFinite() && b[i].isFinite()) {
        cov += (a[i] - ma) * (b[i] - mb)
        va += (a[i] - ma) * (a[i] - ma)
        vb += (b[i] - mb) * (b[i] - mb)
    }
    return if (va > 0.0 && vb > 0.0) cov / sqrt(va * vb) else Double.NaN
}

/** The mean of the readings within [LagCompareHalfWindowSeconds] of [t]; NaN without one. */
private fun DirectionReading.meanAround(t: Double): Double {
    val from = t - LagCompareHalfWindowSeconds
    var i = maxOf(0, indexAtOrBefore(from))
    if (times[i] < from) i++
    var sum = 0.0
    var n = 0
    while (i < times.size && times[i] <= t + LagCompareHalfWindowSeconds) {
        if (values[i].isFinite()) {
            sum += values[i]
            n++
        }
        i++
    }
    return if (n > 0) sum / n else Double.NaN
}

/** Index of the last time not after [t], or -1 when all are after it. */
private fun DirectionReading.indexAtOrBefore(t: Double): Int {
    if (times.isEmpty() || times.first() > t) return -1
    var lo = 0
    var hi = times.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (times[mid] <= t) lo = mid else hi = mid - 1
    }
    return lo
}

/** The reading at [t], straight between the two samples around it; null outside, on a gap or a missing sample. */
@Suppress("ReturnCount")
private fun DirectionReading.at(t: Double): Double? {
    if (t < times.first() || t > times.last()) return null
    val lo = indexAtOrBefore(t)
    if (times[lo] == t) return values[lo].takeIf { it.isFinite() }
    val j = lo + 1
    if (j >= times.size || times[j] - times[lo] > MaxBridgedGapSeconds) return null
    val a = values[lo]
    val b = values[j]
    if (!a.isFinite() || !b.isFinite()) return null
    return a + (t - times[lo]) / (times[j] - times[lo]) * (b - a)
}
