package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.corners.CornerTraces
import com.flappedear.betterdriver.format.fixed

// The words and numbers of the Corner laps screen (FBBD-93): no number below High confidence
// (FBBD-177), none for a value in no known unit, and ≈ on what is not measured (FBBD-174).

/** The time through the corner of [row] as the list shows it: "4.21 s", or "–" without a precise one. */
fun passSecondsText(row: CornerLapRow): String =
    if (row.confidence.doubtful) Missing else row.seconds?.let { "${seconds(it)} s" } ?: Missing

/** A number of [row] ([text]) as the list shows it: none below High confidence (FBBD-177). */
fun gated(text: String, row: CornerLapRow): String = if (row.confidence.doubtful) Missing else text

/**
 * Whether the speed trace may carry numbers: a measured speed in a known unit (FBBD-174). Otherwise
 * the traces show shapes only.
 */
fun speedNumbersShown(traces: CornerTraces): Boolean =
    traces.speedProvenance?.measured == true && traces.speedUnit.isNotBlank()

/** Said where the CornerId is not one of the session's corners (another revision of the track's corners). */
const val NotInSessionText =
    "This corner is not one of this session's corners: the track's corners were changed since."

/** Said quietly where the session has no video of the corner: the traces still show. */
const val NoVideoText = "No video of this corner in this session; the traces show every pass."

/** The line under a pass's time: its braking point and minimum speed, none below High confidence (FBBD-177). */
fun passDetailText(ui: CornerLapsUi, row: CornerLapRow): String {
    // A speed in no known unit is no number to act on.
    val min = if (ui.speedUnit.isEmpty()) Missing else minimumText(row, ui.speedUnit, ui.speedProvenance)
    // The lap names of the summary (FBBD-178): never "optimal" alone, never "best lap" for the best actual lap.
    val tags = listOfNotNull(
        if (row.bestLap) BestActualLapLabel else null,
        if (row.optimal) "In the ${RealisticOptimalLabel.lowercase()}" else null,
    )
    return (listOf("Brakes ${gated(brakingText(row), row)}", "Min ${gated(min, row)}") + tags).joinToString(" · ")
}

/** The compare bar's line: which two laps go side by side, and whether with video. */
fun compareText(ui: CornerLapsUi, pair: Pair<Int, Int>?): String {
    val (a, b) = pair?.let { (x, y) -> ui.row(x) to ui.row(y) } ?: return "Tick two laps to compare"
    val both = a?.video != null && b?.video != null
    val how = if (both) ", videos side by side" else ""
    return "Lap ${a?.lapNumber} against lap ${b?.lapNumber}$how"
}

/**
 * "Speed · 62–118 km/h" for a measured speed in a known unit; just "Speed" (≈ when not measured)
 * otherwise. The range is of the passes of [ui] at High confidence only (FBBD-177): a doubtful pass
 * is drawn as a shape, its numbers shown nowhere.
 */
fun speedLabel(ui: CornerLapsUi, traces: CornerTraces): String {
    val trusted = traces.passes.filter { ui.row(it.lapIndex)?.confidence?.doubtful == false }
    val values = trusted.flatMap { p -> p.speed.flatten().map { it.value } }
    return if (!speedNumbersShown(traces) || values.isEmpty()) {
        marked("Speed", traces.speedProvenance)
    } else {
        "Speed · ${fixed(values.min(), 0)}–${fixed(values.max(), 0)} ${traces.speedUnit}"
    }
}

/** "120 m": how much of the approach the traces show. */
fun approachText(traces: CornerTraces) = "${fixed(traces.entryMeters, 0)} m"

/** "Lap 7", with what stands out about the pass: "Lap 7 · fastest here", "Lap 3 · best actual lap". */
fun passName(row: CornerLapRow): String {
    val what = listOfNotNull(
        if (row.best) "fastest here" else null,
        if (row.bestLap && !row.best) BestActualLapLabel.lowercase() else null,
    )
    return (listOf("Lap ${row.lapNumber}") + what).joinToString(" · ")
}
