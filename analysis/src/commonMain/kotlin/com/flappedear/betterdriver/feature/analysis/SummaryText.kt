package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.format.formatDelta
import com.flappedear.betterdriver.format.formatLapTime

// The summary's words, the same on Android and iOS: the three lap times (FBBD-178), what to work on
// next (FBBD-20), where time goes (FBBD-103) and the G-forces of the whole session.

/**
 * Under the realistic optimal: the time to gain to it and the laps it combines, named as the
 * realistic optimal video names them ([lapsText]). Without one, why: the laps join compatibly
 * through no chain of corners, or a corner has no timed pass (the raw theoretical best says so too).
 */
fun realisticDetail(ui: AnalysisUi): String {
    if (ui.realisticMillis == null) {
        return if (ui.theoreticalMillis == null) "Needs every corner timed" else "No compatible combination of laps"
    }
    val laps = ui.realisticLaps
    val from = when {
        laps.isEmpty() -> "compatible joints only"
        laps == listOf(ui.bestLapNumber) -> "no better combination"
        else -> "from ${lapsText(laps)}"
    }
    return if (ui.realisticGainSeconds == null) from else "${realisticGainText(ui, ::formatDelta)} s · $from"
}

/** "lap 4", "laps 2 and 4", "laps 2, 4 and 7": the laps a composite lap is made of, wherever it is named. */
fun lapsText(laps: List<Int>): String = when (laps.size) {
    0 -> "no lap"
    1 -> "lap ${laps[0]}"
    else -> "laps ${laps.dropLast(1).joinToString(", ")} and ${laps.last()}"
}

/** Under the raw theoretical best: the time to gain to it, and that it was never driven as one lap. */
fun theoreticalDetail(ui: AnalysisUi): String = if (ui.gainSeconds == null) {
    "Best of each corner, never driven as one lap"
} else {
    "${gainText(ui, ::formatDelta)} s · best of each corner, never driven as one lap"
}

/** The best actual lap's time, as the summary shows it. */
fun bestLapText(ui: AnalysisUi): String = formatLapTime(ui.bestLapMillis)

/** The realistic optimal's time, as the summary shows it. */
fun realisticLapText(ui: AnalysisUi): String = realisticText(ui, ::formatLapTime)

/** The raw theoretical best's time, as the summary shows it. */
fun theoreticalLapText(ui: AnalysisUi): String = theoreticalText(ui, ::formatLapTime)

/** "0.18 s": a time to find on the next-session card. */
fun focusSeconds(value: Double): String = "${fixed(value, 2)} s"

/** A focus's time available as the next-session card shows it, or null when unknown. */
fun focusTimeText(row: FocusRow): String? = row.timeText(::focusSeconds)

/** "Where to find time ≈ 0.41 s ›": the line analysis's total, one tap from the Map tab view (UI-190 B). */
fun whereToFindTimeLink(totalSeconds: Double): String = if (totalSeconds > 0.0) {
    "Where to find time ≈ ${focusSeconds(totalSeconds)} ›"
} else {
    "Where to find time ›"
}

/**
 * Over the gains: the best actual lap (FBBD-178's name) against the best of each corner, each row
 * then naming the lap that set that best, as the raw theoretical best by corner does.
 */
fun timeGoesHeading(ui: AnalysisUi): String = "$BestActualLapLabel ${ui.bestLapNumber} against the best of each corner"

/**
 * The lap that set the best through [gain]'s corner: "lap 5", as under each time of the raw
 * theoretical best by corner. Never "best lap 5", which reads as the best actual lap.
 */
fun setOnText(gain: GainRow): String = gain.bestLapNumber?.let { "lap $it" }.orEmpty()

/** [gain]'s time lost as Where time goes shows it: a number at High confidence, a word below. */
fun gainLossText(gain: GainRow): String = gain.lossText(::formatDelta)

/** [loss]'s time lost as Where time goes shows it: a number at High confidence, a word below. */
fun lossLossText(loss: LossRow): String = loss.lossText(::formatDelta)

/** Where a G-G diagram's readings come from, and how to read it. */
fun ggSourceNote(source: GgSource) = when (source) {
    GgSource.Accelerometer -> "From the RaceBox's accelerometer, or the phone's when the RaceBox's did not match " +
        "the GPS, its mounting and tilt corrected against GPS. Left turns on the left; rings every 0.5 g."
    GgSource.Gps -> "Worked out from GPS speed and heading, smoothed: neither the RaceBox's nor the phone's " +
        "accelerometer matched the GPS. Left turns on the left; rings every 0.5 g."
}

/** "Peaks: braking 0.92 g · cornering 1.01 g · accelerating 0.41 g": the whole session's G-forces card. */
fun ggPeaksText(trace: GgTrace): String {
    val peaks = ggPeaks(trace)
    return "Peaks: braking ${fixed(peaks.braking, 2)} g · cornering ${fixed(peaks.cornering, 2)} g · " +
        "accelerating ${fixed(peaks.accelerating, 2)} g"
}

/** A ring's label on the G-G diagram: "0.5 g". */
fun ggRingText(ring: Double): String = "${fixed(ring, 1)} g"
