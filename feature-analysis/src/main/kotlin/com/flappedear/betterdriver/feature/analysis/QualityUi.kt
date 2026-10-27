package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.Verdict
import java.util.Locale
import kotlin.math.abs

/** The card's model of [report]. */
fun qualityUi(report: QualityReport): QualityUi {
    val failures = report.sections.flatMap { section ->
        section.metrics.filter { it.verdict == Verdict.FAIL }.map {
            QualityRow(
                section.title,
                it.label,
                valueText(it.value ?: 0.0, it.unit),
                it.keys.joinToString(", "),
                it.basis == Basis.DERIVED,
            )
        }
    }
    return QualityUi(
        report.count(Verdict.PASS),
        report.count(Verdict.FAIL),
        report.count(Verdict.NOT_MEASURED),
        failures,
    )
}

/** This state with the Data quality card, once the report is read ([quality] not null). */
fun AnalysisState.withQuality(quality: QualityUi?): AnalysisState = when {
    quality == null -> this
    this is AnalysisState.Ready -> AnalysisState.Ready(ui.copy(quality = quality))
    this is AnalysisState.Unavailable -> copy(quality = quality)
    else -> this
}

private const val WholeFrom = 100.0

/** [value] in [unit]: metres to the centimetre, a count whole, others to a tenth below 100. */
internal fun valueText(value: Double, unit: String): String {
    val number = when {
        unit == "m" -> String.format(Locale.ROOT, "%.2f", value)
        unit.isEmpty() && value % 1.0 == 0.0 -> String.format(Locale.ROOT, "%.0f", value)
        abs(value) >= WholeFrom -> String.format(Locale.ROOT, "%.0f", value)
        else -> String.format(Locale.ROOT, "%.1f", value)
    }
    return if (unit.isEmpty()) number else "$number $unit"
}
