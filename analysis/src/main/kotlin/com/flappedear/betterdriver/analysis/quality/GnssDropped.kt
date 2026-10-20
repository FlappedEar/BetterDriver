package com.flappedear.betterdriver.analysis.quality

import java.util.Locale
import kotlin.math.roundToLong

// Dropped GNSS samples in the session validation report (FBBD-183), against FBBD-8 and FBBD-170,
// counted from the receiver's first fix (FBBD-259).

private const val MaxDroppedPercent = 0.5
private const val Median = 0.5

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
 *
 * The verdict counts from the receiver's first fix (FBBD-259): before it the receiver has no data the
 * app uses, and a cold start's link can lag and lose messages for a minute (2026-10-20 08:59: 124 of
 * 137 missing before the fix at 114 s, 0.07 % after, yet a FAIL at 0.67 %). The losses before it,
 * up to the step that ends on the first fix, are reported for information.
 */
internal fun droppedMetrics(
    s: GnssSamples,
    acrossDrop: BooleanArray,
    raceBox: Boolean,
    skipsWithoutFix: Boolean,
): List<QualityMetric> {
    val steps = receiverSteps(s, acrossDrop)
    val usual = nearestRank(steps.map { it.millis.toDouble() }, Median)
    if (usual == null || usual <= 0) {
        val why = "the receiver gave no fix times, so missing samples cannot be told from late ones"
        return listOf(notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, why))
    }
    val firstFix = (0 until s.size).firstOrNull { s.hasFix(it) && s.utc[it] != GnssSamples.NoUtc }
    val lost = tally(steps, usual, firstFix ?: Int.MAX_VALUE, raceBox, skipsWithoutFix)
    val missing = lost.missing
    val beforeFix = lost.beforeFix
    val before = beforeFixInfo(beforeFix, firstFix?.let { s.t[it] })
    val metric = if (firstFix == null) {
        notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, NoFixText)
    } else {
        val received = (firstFix until s.size).count { s.utc[it] != GnssSamples.NoUtc }
        val expected = received + missing + lost.wholeSecond
        val percent = missing * Percent / expected
        val skips = lost.wholeSecond + lost.skipsBeforeFix
        val how = "$missing of $expected expected samples missing from the receiver's fix times since its " +
            "first fix (usual step ${usual.roundToLong()} ms), besides $skips single whole-second messages " +
            "the RaceBox does not send" + (if (skipsWithoutFix) "" else " with a fix") + "; $beforeFix missing " +
            "before the first fix, counted apart"
        val ok = percent <= MaxDroppedPercent
        judged("gnss.dropped", "Dropped samples", percent, "%", ok, Basis.MEASURED, DroppedCriterion, how)
    }
    return listOf(metric, before)
}

/** Samples lost in [steps], those up to the step ending on sample [firstFix] apart, the RaceBox's skips apart. */
private class Tally(val missing: Long, val beforeFix: Long, val wholeSecond: Long, val skipsBeforeFix: Long)

private fun tally(
    steps: List<ReceiverStep>,
    usual: Double,
    firstFix: Int,
    raceBox: Boolean,
    skipsWithoutFix: Boolean,
): Tally {
    var missing = 0L
    var beforeFix = 0L
    var wholeSecond = 0L
    var skipsBeforeFix = 0L
    for (step in steps) {
        val lost = maxOf(0L, (step.millis / usual).roundToLong() - 1)
        val before = step.to <= firstFix
        val skip = raceBox && lost == 1L && isWholeSecondSkip(step, usual, skipsWithoutFix)
        when {
            skip && before -> skipsBeforeFix++
            skip -> wholeSecond++
            before -> beforeFix += lost
            else -> missing += lost
        }
    }
    return Tally(missing, beforeFix, wholeSecond, skipsBeforeFix)
}

internal const val NoFixText = "the receiver had no fix in this session, so no sample the app uses could be lost"

/** The samples missing before the receiver's first fix (at [firstFixMicros], session time), for information. */
internal fun beforeFixInfo(missing: Long, firstFixMicros: Long?): QualityMetric {
    val at = firstFixMicros?.let { "first fix ${"%.1f".format(Locale.ROOT, seconds(it))} s in" }
        ?: "no fix in this session"
    return info(
        "gnss.dropped_before_fix", "Dropped before first fix", missing.toDouble(), "", Basis.MEASURED,
        "samples missing from the receiver's fix times before its first fix ($at); not judged (FBBD-259)",
    )
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
internal fun wholeSecondSkips(
    s: GnssSamples,
    acrossDrop: BooleanArray,
    raceBox: Boolean,
    skipsWithoutFix: Boolean,
): Long {
    val steps = if (raceBox) receiverSteps(s, acrossDrop) else emptyList()
    val usual = nearestRank(steps.map { it.millis.toDouble() }, Median)?.takeIf { it > 0 }
    return if (usual == null) 0L else steps.count { isWholeSecondSkip(it, usual, skipsWithoutFix) }.toLong()
}

/**
 * A step between consecutive fix times, from [fromUtc], [millis] long, ending on sample [to];
 * [fixBothSides] when both samples had a fix.
 */
private class ReceiverStep(val fromUtc: Long, val millis: Long, val fixBothSides: Boolean, val to: Int)

/** Steps between consecutive fix times of the receiver; steps across a link drop and backwards left out. */
private fun receiverSteps(s: GnssSamples, acrossDrop: BooleanArray): List<ReceiverStep> {
    val steps = mutableListOf<ReceiverStep>()
    var previous = -1
    var dropBetween = false
    for (i in 0 until s.size) {
        if (s.utc[i] != GnssSamples.NoUtc) {
            if (previous >= 0 && !dropBetween && s.utc[i] > s.utc[previous]) {
                steps += ReceiverStep(
                    s.utc[previous], s.utc[i] - s.utc[previous], s.hasFix(previous) && s.hasFix(i), i,
                )
            }
            previous = i
            dropBetween = false
        }
        if (acrossDrop[i]) dropBetween = true
    }
    return steps
}
