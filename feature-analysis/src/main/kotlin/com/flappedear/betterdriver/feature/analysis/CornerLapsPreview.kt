@file:Suppress("MagicNumber") // Example data.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.corners.CornerPass
import com.flappedear.betterdriver.analysis.corners.CornerTraces
import com.flappedear.betterdriver.analysis.corners.PassTrace
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.PI
import kotlin.math.cos

/**
 * Five example passes through Corner 4 with their traces (FBBD-93): the best actual lap's, the
 * realistic optimal's, one of Medium confidence, and video on all but one ([withVideo] false: none,
 * as a session recorded without a camera). The Corner laps screen's preview and layout tests.
 */
fun previewCornerLaps(withVideo: Boolean = true): CornerLapsUi {
    val laps = listOf(
        // Lap index, number, time through the corner, braking point before the entry, minimum speed.
        PreviewPass(2, 3, 5.18, 62.0, 84.0),
        PreviewPass(4, 5, 5.24, 58.0, 82.5),
        PreviewPass(1, 2, 5.31, 66.0, 81.0),
        PreviewPass(3, 4, 5.42, 71.0, 79.5),
        PreviewPass(0, 1, 5.66, 75.0, 76.0),
    )
    val passes = laps.map {
        CornerPass(
            it.index, it.number, 30.0, 30.0 + it.seconds, it.seconds, 28.0, it.braking, false, it.minimum, "km/h",
        )
    }
    val traces = CornerTraces(
        lengthMeters = 280.0,
        entryMeters = 120.0,
        exitMeters = 220.0,
        passes = laps.map { previewTrace(it) },
        speedUnit = "km/h",
        speedProvenance = Provenance.MEASURED_GNSS_RECEIVER,
        brakeProvenance = Provenance.MEASURED_OBD,
        throttleProvenance = Provenance.MEASURED_OBD,
    )
    return cornerLapsUi(
        "s5",
        "Corner 4",
        passes,
        video = { p ->
            if (withVideo && p.lapIndex != 0) CornerVideoUi("/preview/lap-${p.lapNumber}.mp4", 1_000, 7_000) else null
        },
        reviewable = { true },
        speedProvenance = Provenance.MEASURED_GNSS_RECEIVER,
        marks = PassMarks(
            bestLapIndex = 1,
            optimalLapIndex = 2,
            confidence = {
                if (it.lapIndex == 3) ConfidenceUi(Confidence.MEDIUM, "a GPS gap in this corner") else ConfidenceUi.High
            },
        ),
        traces = traces,
    )
}

private class PreviewPass(
    val index: Int,
    val number: Int,
    val seconds: Double,
    val braking: Double,
    val minimum: Double,
)

/** Down from 140 km/h to the pass's minimum through the corner and back up, braking from its braking point. */
private fun previewTrace(p: PreviewPass): PassTrace {
    val brakeAt = 120.0 - p.braking
    val apex = 175.0
    val points = (0..56).map { it * 5.0 }
    fun speed(m: Double): Double = when {
        m < brakeAt -> 140.0
        m < apex -> p.minimum + (140.0 - p.minimum) * (1 + cos(PI * (m - brakeAt) / (apex - brakeAt))) / 2
        else -> p.minimum + (m - apex) * 0.35
    }
    fun brake(m: Double) = if (m in brakeAt..(brakeAt + 0.8 * (apex - brakeAt))) 60.0 - (m - brakeAt) * 0.4 else 0.0
    fun throttle(m: Double) = when {
        m < brakeAt -> 100.0
        m < apex - 10.0 -> 0.0
        else -> minOf(100.0, (m - apex + 10.0) * 1.5)
    }
    return PassTrace(
        p.index,
        speed = listOf(points.map { ChartPoint(it, speed(it)) }),
        brake = listOf(points.map { ChartPoint(it, brake(it)) }),
        throttle = listOf(points.map { ChartPoint(it, throttle(it)) }),
    )
}
