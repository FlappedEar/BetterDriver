package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.recorder.Provenance

// No precise advice from weak evidence (FBBD-174): a value that is not measured (calculated,
// inferred, fused, interpolated, simulated or of unknown source) is never shown as measured. It
// carries one small mark before it, and the card it is on says once what the mark means.

/** The mark before a value that is not measured. */
const val NotMeasuredMark = "≈"

/** What [NotMeasuredMark] means, on every card that shows one. */
const val ProvenanceLegendText = "$NotMeasuredMark calculated or inferred, not measured"

/** Test tag of the legend line. */
const val ProvenanceLegendTag = "provenance-legend"

/** The value comes from somewhere other than a measurement. Null (not known) is not marked. */
val Provenance?.notMeasured: Boolean get() = this != null && !measured

/** [text] with [NotMeasuredMark] before it when [provenance] is not measured; a missing value ("–") never. */
fun marked(text: String, provenance: Provenance?): String =
    if (provenance.notMeasured && text != Missing) NotMeasuredMark + text else text

/** A value that is not there. */
internal const val Missing = "–"

/** The raw theoretical best as the summary shows it, marked as calculated (FBBD-174). */
internal fun theoreticalText(ui: AnalysisUi, format: (Long) -> String): String =
    marked(ui.theoreticalMillis?.let(format) ?: Missing, TheoreticalProvenance)

/** The realistic optimal as the summary shows it (FBBD-178), marked as calculated like the raw theoretical best. */
internal fun realisticText(ui: AnalysisUi, format: (Long) -> String): String =
    marked(ui.realisticMillis?.let(format) ?: Missing, TheoreticalProvenance)

/** The time to gain to the realistic optimal, as [gainText] says the time to gain to the raw theoretical best. */
internal fun realisticGainText(ui: AnalysisUi, format: (Double) -> String): String =
    gainText(ui.copy(gainSeconds = ui.realisticGainSeconds), format)

/**
 * The time to gain to the raw theoretical best, marked as calculated; below High confidence (FBBD-177)
 * said in a word instead ([timeText]), which carries no mark.
 */
internal fun gainText(ui: AnalysisUi, format: (Double) -> String): String {
    val gain = ui.gainSeconds ?: return marked(Missing, TheoreticalProvenance)
    val text = timeText(gain, ui.gainConfidence) { format(-it) }
    return if (ui.gainConfidence.doubtful) text else marked(text, TheoreticalProvenance)
}

/** The summary's names for the three lap times (FBBD-178): distinct wherever they appear. */
const val BestActualLapLabel = "Best actual lap"
const val RealisticOptimalLabel = "Realistic optimal"
const val RawTheoreticalBestLabel = "Raw theoretical best"
