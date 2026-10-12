package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.roundToLong

// The GNSS part of the session validation report (FBBD-183), against FBBD-8 and FBBD-46: sample
// rate, dropped samples and gaps. Reconnects and the position noise while parked have files of their own.

private const val MinRateHz = 24.5
private const val MaxDroppedPercent = 0.5
internal const val MaxGapMicros = 200_000L
private const val P1 = 0.01
private const val Median = 0.5

/** At most this many gaps are listed. */
private const val MaxGapNotes = 50

/**
 * The session's GNSS sample sets (those with any [GnssChannels] value) in time order. [t] is when
 * each arrived, on the session clock; [utc] the receiver's own time of the fix in ms, or [NoUtc].
 */
internal class GnssSamples(sets: List<SampleSet>) {
    val t: LongArray
    val utc: LongArray
    val lat: DoubleArray
    val lon: DoubleArray
    val speed: DoubleArray

    init {
        val gnss = sets.filter { s -> s.values.any { it.channelId in GnssChannels.IDS } }.sortedBy { it.tMicros }
        t = LongArray(gnss.size) { gnss[it].tMicros }
        utc = LongArray(gnss.size) { i -> value(gnss[i], GnssChannels.UTC_MS.id)?.roundToLong() ?: NoUtc }
        lat = DoubleArray(gnss.size) { i -> value(gnss[i], GnssChannels.LAT.id) ?: Double.NaN }
        lon = DoubleArray(gnss.size) { i -> value(gnss[i], GnssChannels.LON.id) ?: Double.NaN }
        speed = DoubleArray(gnss.size) { i -> value(gnss[i], GnssChannels.SPEED_KMH.id) ?: Double.NaN }
    }

    val size: Int get() = t.size

    /** Receivers without a fix can report 0, 0: that is no position. */
    fun hasFix(i: Int) = lat[i].isFinite() && lon[i].isFinite() && !(lat[i] == 0.0 && lon[i] == 0.0)

    /** The index of the last sample at or before [micros], or -1. */
    fun lastAtOrBefore(micros: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= micros) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        const val NoUtc = Long.MIN_VALUE

        private fun value(set: SampleSet, id: Int): Double? =
            set.values.firstOrNull { it.channelId == id }?.value?.takeIf { it.isFinite() }
    }
}

/** The GNSS section: [samples] with the link drops logged in [events]; [endMicros] is the session's end. */
internal fun gnssSection(samples: GnssSamples, events: List<RecorderEvent>, endMicros: Long): QualitySection {
    if (samples.size < 2) {
        val why = "fewer than two GNSS samples in this session"
        return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", RateCriterion, why)))
    }
    val drops = reconnects(samples, events)
    // A pair of samples a logged drop falls between is a reconnect, not a gap (FBBD-8).
    val acrossDrop = BooleanArray(samples.size)
    drops.forEach { acrossDrop[it.before] = it.after != null }
    val gaps = gaps(samples, acrossDrop)
    val metrics = buildList {
        add(rate(samples, acrossDrop))
        add(p1Rate(samples, drops))
        add(dropped(samples, acrossDrop))
        addAll(gapMetrics(gaps))
        addAll(reconnectMetrics(samples, drops, endMicros))
        add(stationaryNoise(samples))
    }
    val gapNotes = gaps.long.take(MaxGapNotes).map { (at, gap) -> QualityNote(at, "gap ${ms(gap)} ms") }
    val notes = drops.map { d -> QualityNote(d.dropMicros, reconnectText(samples, d, endMicros)) } + gapNotes
    return QualitySection("GNSS", metrics, notes.sortedBy { it.tMicros })
}

private const val RateCriterion = ">= 24.5 Hz over the session (FBBD-8)"

/** Samples a second while the link was up: intervals across a logged drop are left out. */
private fun rate(s: GnssSamples, acrossDrop: BooleanArray): QualityMetric {
    var intervals = 0
    var span = 0L
    for (i in 0 until s.size - 1) {
        if (acrossDrop[i]) continue
        intervals++
        span += s.t[i + 1] - s.t[i]
    }
    if (span <= 0) return notMeasured("gnss.rate", "Average sample rate", RateCriterion, "no time between samples")
    val hz = intervals * MicrosPerSecond.toDouble() / span
    val how = "samples received per second on the phone clock, between the first and last sample, " +
        "link drops left out"
    return judged("gnss.rate", "Average sample rate", hz, "Hz", hz >= MinRateHz, Basis.MEASURED, RateCriterion, how)
}

/** The 1st percentile of samples per whole second, seconds touching a link drop left out. */
private fun p1Rate(s: GnssSamples, drops: List<Reconnect>): QualityMetric {
    val t0 = s.t.first()
    val seconds = ((s.t.last() - t0) / MicrosPerSecond).toInt()
    val counts = IntArray(seconds)
    for (t in s.t) {
        val k = ((t - t0) / MicrosPerSecond).toInt()
        if (k < seconds) counts[k]++
    }
    val windows = drops.map { d -> s.t[d.before] to (d.after?.let { s.t[it] } ?: Long.MAX_VALUE) }
    val kept = (0 until seconds).filter { k ->
        val from = t0 + k * MicrosPerSecond
        val to = from + MicrosPerSecond
        windows.none { (a, b) -> a < to && b > from }
    }.map { counts[it].toDouble() }
    val how = "samples in each whole second from the first sample, 1st percentile (nearest rank), " +
        "seconds with a link drop left out"
    return info("gnss.rate_p1", "Sample rate, 1st percentile", nearestRank(kept, P1), "Hz", Basis.MEASURED, how)
}

private const val DroppedCriterion = "<= 0.5 % of expected, link drops left out (FBBD-8)"

/**
 * Samples missing from the receiver's own fix times: each step longer than the usual one (the
 * median) hides round(step / usual) - 1 samples. Steps across a link drop are left out.
 */
private fun dropped(s: GnssSamples, acrossDrop: BooleanArray): QualityMetric {
    val steps = receiverSteps(s, acrossDrop)
    val usual = nearestRank(steps.map { it.toDouble() }, Median)
    if (usual == null || usual <= 0) {
        val why = "the receiver gave no fix times, so missing samples cannot be told from late ones"
        return notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, why)
    }
    val received = s.utc.count { it != GnssSamples.NoUtc }
    val missing = steps.sumOf { step -> maxOf(0L, (step / usual).roundToLong() - 1) }
    val percent = missing * Percent / (received + missing)
    val how = "$missing of ${received + missing} expected samples missing from the receiver's fix times " +
        "(usual step ${usual.roundToLong()} ms)"
    val ok = percent <= MaxDroppedPercent
    return judged("gnss.dropped", "Dropped samples", percent, "%", ok, Basis.MEASURED, DroppedCriterion, how)
}

/** Steps between consecutive fix times of the receiver, in ms; steps across a link drop and backwards left out. */
private fun receiverSteps(s: GnssSamples, acrossDrop: BooleanArray): List<Long> {
    val steps = mutableListOf<Long>()
    var previous = -1
    var dropBetween = false
    for (i in 0 until s.size) {
        if (s.utc[i] != GnssSamples.NoUtc) {
            if (previous >= 0 && !dropBetween && s.utc[i] > s.utc[previous]) steps += s.utc[i] - s.utc[previous]
            previous = i
            dropBetween = false
        }
        if (acrossDrop[i]) dropBetween = true
    }
    return steps
}

/**
 * Gaps between samples in µs, on the receiver's clock when it has one. [long] are those over 200 ms,
 * each with the session time of the sample after it.
 */
private class Gaps(val max: Long, val mean: Double, val long: List<Pair<Long, Long>>, val receiverClock: Boolean)

private fun gaps(s: GnssSamples, acrossDrop: BooleanArray): Gaps {
    val receiverClock = s.utc.count { it != GnssSamples.NoUtc } >= 2
    val all = mutableListOf<Pair<Long, Long>>()
    var previous = -1
    var dropBetween = false
    for (i in 0 until s.size) {
        if (!receiverClock || s.utc[i] != GnssSamples.NoUtc) {
            val gap = if (previous < 0 || dropBetween) 0L else gap(s, previous, i, receiverClock)
            if (gap > 0) all += s.t[i] to gap
            previous = i
            dropBetween = false
        }
        if (acrossDrop[i]) dropBetween = true
    }
    val max = all.maxOfOrNull { it.second } ?: 0L
    val mean = if (all.isEmpty()) 0.0 else all.sumOf { it.second }.toDouble() / all.size
    return Gaps(max, mean, all.filter { it.second > MaxGapMicros }, receiverClock)
}

private fun gap(s: GnssSamples, from: Int, to: Int, receiverClock: Boolean) =
    if (receiverClock) (s.utc[to] - s.utc[from]) * MicrosPerMilli else s.t[to] - s.t[from]

private fun gapMetrics(g: Gaps): List<QualityMetric> {
    val clock = if (g.receiverClock) {
        "the receiver's fix times"
    } else {
        "arrival on the phone (the receiver gave no fix times)"
    }
    val how = "between consecutive samples, by $clock"
    return listOf(
        judged(
            "gnss.max_gap", "Longest gap", millis(g.max), "ms", g.max <= MaxGapMicros, Basis.MEASURED,
            "<= 200 ms, link drops left out (FBBD-8)", how,
        ),
        info("gnss.mean_gap", "Mean gap", g.mean / MicrosPerMilli, "ms", Basis.MEASURED, how),
        info(
            "gnss.long_gaps", "Gaps over 200 ms", g.long.size.toDouble(), "", Basis.MEASURED,
            "$how; each listed with its time",
        ),
    )
}
