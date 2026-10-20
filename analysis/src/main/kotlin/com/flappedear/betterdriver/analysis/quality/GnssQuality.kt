package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import java.util.Locale
import kotlin.math.roundToLong

// The GNSS part of the session validation report (FBBD-183), against FBBD-8 and FBBD-46: sample
// rate, dropped samples and gaps. Reconnects and the position noise while parked have files of their own.

// Arek (2026-10-13): above 24 Hz is good, below 20 Hz would worry him. Missing messages are
// judged on their own by the dropped-samples check.
private const val MinRateHz = 24.0
internal const val MaxGapMicros = 200_000L
private const val P1 = 0.01

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

/**
 * The GNSS section: [samples] with the link drops logged in [events]; [endMicros] is the session's end.
 * [raceBox] says the receiver was a RaceBox, whose whole-second skip (FBBD-170) is counted apart;
 * [skipsWithoutFix] that it was a Micro, which skips it without a fix too.
 */
internal fun gnssSection(
    samples: GnssSamples,
    events: List<RecorderEvent>,
    endMicros: Long,
    raceBox: Boolean = false,
    skipsWithoutFix: Boolean = false,
): QualitySection {
    if (samples.size < 2) {
        val why = "fewer than two GNSS samples in this session"
        return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", RateCriterion, why)))
    }
    val drops = reconnects(samples, events)
    val underCover = underCoverFrom(samples)
    // A pair of samples a logged drop falls between is a reconnect, not a gap (FBBD-8).
    val acrossDrop = BooleanArray(samples.size)
    drops.forEach { acrossDrop[it.before] = it.after != null }
    val gaps = gaps(samples, acrossDrop)
    val metrics = buildList {
        add(rate(samples, acrossDrop, wholeSecondSkips(samples, acrossDrop, raceBox, skipsWithoutFix)))
        add(p1Rate(samples, drops))
        addAll(droppedMetrics(samples, acrossDrop, raceBox, skipsWithoutFix))
        addAll(gapMetrics(gaps))
        addAll(reconnectMetrics(samples, drops, endMicros, underCover))
        add(stationaryNoise(samples))
    }
    val earlyNotes = gaps.beforeFix.filter { it.second > MaxGapMicros }
        .map { (at, gap) -> QualityNote(at, "gap ${ms(gap)} ms before the first fix, not judged") }
    val gapNotes = (earlyNotes + gaps.long.map { (at, gap) -> QualityNote(at, "gap ${ms(gap)} ms") })
        .take(MaxGapNotes)
    val notes = drops.map { d ->
        QualityNote(d.dropMicros, reconnectText(samples, d, endMicros, underCover))
    } + gapNotes
    return QualitySection("GNSS", metrics, notes.sortedBy { it.tMicros })
}

private const val RateCriterion =
    ">= 24 Hz over the session, the RaceBox's whole-second skip left out (FBBD-8, FBBD-170)"

/**
 * Samples a second while the link was up: intervals across a logged drop are left out. The [skips]
 * of the RaceBox's whole-second message (FBBD-170) count as received, as for dropped samples: with a
 * fix it sends 24 a second, so one sample really lost anywhere took the rate under 24 Hz (Arek's
 * 2026-10-14 13:02 log: 23.99 Hz with 0.07 % dropped). Missing samples are judged by that check.
 */
private fun rate(s: GnssSamples, acrossDrop: BooleanArray, skips: Long): QualityMetric {
    var intervals = 0
    var span = 0L
    for (i in 0 until s.size - 1) {
        if (acrossDrop[i]) continue
        intervals++
        span += s.t[i + 1] - s.t[i]
    }
    if (span <= 0) return notMeasured("gnss.rate", "Average sample rate", RateCriterion, "no time between samples")
    val received = intervals * MicrosPerSecond.toDouble() / span
    val hz = (intervals + skips) * MicrosPerSecond.toDouble() / span
    val counted = if (skips > 0) {
        ", with the $skips whole-second messages the RaceBox does not send with a fix counted " +
            "(received: ${"%.2f".format(Locale.ROOT, received)} Hz)"
    } else {
        ""
    }
    val how = "samples received per second on the phone clock, between the first and last sample, " +
        "link drops left out$counted"
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

/**
 * Gaps between samples in µs, on the receiver's clock when it has one. [long] are those over 200 ms,
 * each with the session time of the sample after it. As for dropped samples (FBBD-259), the gaps up to
 * the one ending on the receiver's first fix are kept apart in [beforeFix] (FBBD-273): [max], [mean]
 * and [long] are from the first fix on. [firstFixMicros] is null when the receiver had no fix, and
 * then every gap is judged.
 */
private class Gaps(
    val max: Long,
    val mean: Double,
    val long: List<Pair<Long, Long>>,
    val receiverClock: Boolean,
    val beforeFix: List<Pair<Long, Long>>,
    val firstFixMicros: Long?,
)

private fun gaps(s: GnssSamples, acrossDrop: BooleanArray): Gaps {
    val receiverClock = s.utc.count { it != GnssSamples.NoUtc } >= 2
    val firstFix = (0 until s.size).firstOrNull { s.hasFix(it) && (!receiverClock || s.utc[it] != GnssSamples.NoUtc) }
    // Each gap with the index of the sample that ends it; without any fix every gap is judged.
    val (beforeFix, all) = steps(s, acrossDrop, receiverClock)
        .partition { (to, _) -> firstFix != null && to <= firstFix }
        .let { (a, b) -> a.map { (to, gap) -> s.t[to] to gap } to b.map { (to, gap) -> s.t[to] to gap } }
    val max = all.maxOfOrNull { it.second } ?: 0L
    val mean = if (all.isEmpty()) 0.0 else all.sumOf { it.second }.toDouble() / all.size
    val firstFixMicros = firstFix?.let { s.t[it] }
    return Gaps(max, mean, all.filter { it.second > MaxGapMicros }, receiverClock, beforeFix, firstFixMicros)
}

/** The gaps between consecutive samples (by the receiver's clock when it has one), each with the index ending it. */
private fun steps(s: GnssSamples, acrossDrop: BooleanArray, receiverClock: Boolean): List<Pair<Int, Long>> {
    val all = mutableListOf<Pair<Int, Long>>()
    var previous = -1
    var dropBetween = false
    for (i in 0 until s.size) {
        if (!receiverClock || s.utc[i] != GnssSamples.NoUtc) {
            val gap = if (previous < 0 || dropBetween) 0L else gap(s, previous, i, receiverClock)
            if (gap > 0) all += i to gap
            previous = i
            dropBetween = false
        }
        if (acrossDrop[i]) dropBetween = true
    }
    return all
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
    val judgedHow = if (g.firstFixMicros == null) how else "$how, from the receiver's first fix"
    return listOf(
        judged(
            "gnss.max_gap", "Longest gap", millis(g.max), "ms", g.max <= MaxGapMicros, Basis.MEASURED,
            "<= 200 ms, link drops left out (FBBD-8)", judgedHow,
        ),
        maxGapBeforeFixInfo(g.beforeFix.maxOfOrNull { it.second }?.let(::millis) ?: 0.0, g.firstFixMicros),
        info("gnss.mean_gap", "Mean gap", g.mean / MicrosPerMilli, "ms", Basis.MEASURED, judgedHow),
        info(
            "gnss.long_gaps", "Gaps over 200 ms", g.long.size.toDouble(), "", Basis.MEASURED,
            "$judgedHow; each listed with its time",
        ),
    )
}

/** The longest gap before the receiver's first fix (at [firstFixMicros], session time), for information (FBBD-273). */
internal fun maxGapBeforeFixInfo(maxMillis: Double, firstFixMicros: Long?): QualityMetric {
    val at = firstFixMicros?.let { "first fix ${"%.1f".format(Locale.ROOT, seconds(it))} s in" }
        ?: "no fix in this session, so every gap is judged"
    return info(
        "gnss.max_gap_before_fix", "Longest gap before first fix", maxMillis, "ms", Basis.MEASURED,
        "between consecutive samples up to the receiver's first fix ($at); not judged (FBBD-273)",
    )
}
