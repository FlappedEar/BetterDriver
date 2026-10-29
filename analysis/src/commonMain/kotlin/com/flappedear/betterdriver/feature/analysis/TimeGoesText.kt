package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.format.formatDelta

// The summary's words for where time goes (FBBD-103) and the whole session's G-forces, the same on
// Android and iOS.

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
