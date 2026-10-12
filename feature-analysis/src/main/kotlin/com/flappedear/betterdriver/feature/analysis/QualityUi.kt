package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.Verdict
import java.util.Locale
import kotlin.math.abs

/**
 * One checked value on the Data quality card: [value] with its unit, [pass] against its criterion.
 * [derived] marks a value worked out by a heuristic or an estimate, not read straight from the data.
 */
data class QualityRow(
    val section: String,
    val label: String,
    val value: String,
    val pass: Boolean,
    val derived: Boolean,
)

/**
 * The Data quality card (FBBD-183): the session validation report's checked values, failures first,
 * and the criteria this session could not be checked against. The full report, with how each value
 * was obtained, is in the debug logs.
 */
data class QualityUi(val passed: Int, val failed: Int, val rows: List<QualityRow>, val notMeasured: List<String>)

/** The card's model of [report]; values for information only are left to the full report. */
fun qualityUi(report: QualityReport): QualityUi {
    val rows = report.sections.flatMap { section ->
        section.metrics.filter { it.verdict == Verdict.PASS || it.verdict == Verdict.FAIL }.map {
            QualityRow(
                section.title,
                it.label,
                valueText(it.value ?: 0.0, it.unit),
                it.verdict == Verdict.PASS,
                it.basis == Basis.DERIVED,
            )
        }
    }
    val notMeasured = report.metrics.filter { it.verdict == Verdict.NOT_MEASURED }.map { it.label }.distinct()
    return QualityUi(
        report.count(Verdict.PASS),
        report.count(Verdict.FAIL),
        rows.sortedBy { it.pass },
        notMeasured,
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
