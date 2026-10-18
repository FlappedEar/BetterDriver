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

/**
 * The GNSS section of a session on Demo GNSS or the demo track (FBBD-233): its positions are made
 * up, so nothing about a receiver is measured, and such a session is never receiver evidence.
 */
internal fun demoGnssSection(): QualitySection {
    val why = "demo receiver: the positions are made up by the app, so the receiver checks need a real one"
    return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", RateCriterion, why)))
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
    // A pair of samples a logged drop falls between is a reconnect, not a gap (FBBD-8).
    val acrossDrop = BooleanArray(samples.size)
    drops.forEach { acrossDrop[it.before] = it.after != null }
    val gaps = gaps(samples, acrossDrop)
    val metrics = buildList {
        add(rate(samples, acrossDrop, wholeSecondSkips(samples, acrossDrop, raceBox, skipsWithoutFix)))
        add(p1Rate(samples, drops))
        add(dropped(samples, acrossDrop, raceBox, skipsWithoutFix))
        addAll(gapMetrics(gaps))
        addAll(reconnectMetrics(samples, drops, endMicros))
        add(stationaryNoise(samples))
    }
    val gapNotes = gaps.long.take(MaxGapNotes).map { (at, gap) -> QualityNote(at, "gap ${ms(gap)} ms") }
    val notes = drops.map { d -> QualityNote(d.dropMicros, reconnectText(samples, d, endMicros)) } + gapNotes
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

private const val DroppedCriterion =
    "<= 0.5 % of expected, link drops and the RaceBox's whole-second skip left out (FBBD-8, FBBD-170)"

private const val MillisPerSecond = 1_000L

/**
 * Samples missing from the receiver's fix times. A single sample missing on a whole second is left
 * out of the verdict and counted on its own: with a fix the RaceBox does not send its whole-second
 * message (FBBD-170), on both units Arek has. The 2026-10-14 log proved it is the device, not the
 * link: the same link carried all 25 messages a second whenever the fix was lost (1500 in 1500
 * notifications, none missing). Arek accepts the 24 Hz that leaves ("> 24 sounds good", FBBD-218).
 * The Micro skips it before its first fix too (2026-10-16 midday: 63 a minute, all on the whole
 * second, through 2 min without a fix): with [skipsWithoutFix] the skip is left out either way.
 * A Mini sends it without a fix, so a loss there stays a loss.
 */
private fun dropped(
    s: GnssSamples,
    acrossDrop: BooleanArray,
    raceBox: Boolean,
    skipsWithoutFix: Boolean,
): QualityMetric {
    val steps = receiverSteps(s, acrossDrop)
    val usual = nearestRank(steps.map { it.millis.toDouble() }, Median)
    if (usual == null || usual <= 0) {
        val why = "the receiver gave no fix times, so missing samples cannot be told from late ones"
        return notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, why)
    }
    val received = s.utc.count { it != GnssSamples.NoUtc }
    var missing = 0L
    var wholeSecond = 0L
    for (step in steps) {
        val lost = maxOf(0L, (step.millis / usual).roundToLong() - 1)
        if (raceBox && lost == 1L && isWholeSecondSkip(step, usual, skipsWithoutFix)) wholeSecond++ else missing += lost
    }
    val expected = received + missing + wholeSecond
    val percent = missing * Percent / expected
    val how = "$missing of $expected expected samples missing from the receiver's fix times " +
        "(usual step ${usual.roundToLong()} ms), besides $wholeSecond single whole-second messages the RaceBox " +
        "does not send" + if (skipsWithoutFix) "" else " with a fix"
    val ok = percent <= MaxDroppedPercent
    return judged("gnss.dropped", "Dropped samples", percent, "%", ok, Basis.MEASURED, DroppedCriterion, how)
}

/**
 * A single sample lost on a whole second between two fixes: the RaceBox's skip (FBBD-170). Its middle
 * is within half a [usual] step of a whole second.
 */
private fun isWholeSecondSkip(step: ReceiverStep, usual: Double, skipsWithoutFix: Boolean): Boolean {
    val ms = Math.floorMod(step.fromUtc + step.millis / 2, MillisPerSecond)
    val onSecond = ms < usual / 2 || ms > MillisPerSecond - usual / 2
    return (step.fixBothSides || skipsWithoutFix) && (step.millis / usual).roundToLong() == 2L && onSecond
}

/** How many of the RaceBox's whole-second messages are missing with a fix (FBBD-170); 0 for other receivers. */
private fun wholeSecondSkips(
    s: GnssSamples,
    acrossDrop: BooleanArray,
    raceBox: Boolean,
    skipsWithoutFix: Boolean,
): Long {
    val steps = if (raceBox) receiverSteps(s, acrossDrop) else emptyList()
    val usual = nearestRank(steps.map { it.millis.toDouble() }, Median)?.takeIf { it > 0 }
    return if (usual == null) 0L else steps.count { isWholeSecondSkip(it, usual, skipsWithoutFix) }.toLong()
}

/** A step between consecutive fix times, from [fromUtc], [millis] long; [fixBothSides] when both samples had a fix. */
private class ReceiverStep(val fromUtc: Long, val millis: Long, val fixBothSides: Boolean)

/** Steps between consecutive fix times of the receiver; steps across a link drop and backwards left out. */
private fun receiverSteps(s: GnssSamples, acrossDrop: BooleanArray): List<ReceiverStep> {
    val steps = mutableListOf<ReceiverStep>()
    var previous = -1
    var dropBetween = false
    for (i in 0 until s.size) {
        if (s.utc[i] != GnssSamples.NoUtc) {
            if (previous >= 0 && !dropBetween && s.utc[i] > s.utc[previous]) {
                steps += ReceiverStep(s.utc[previous], s.utc[i] - s.utc[previous], s.hasFix(previous) && s.hasFix(i))
            }
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
