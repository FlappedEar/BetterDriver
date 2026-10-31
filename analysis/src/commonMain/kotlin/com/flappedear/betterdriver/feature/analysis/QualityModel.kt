package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs

/**
 * A failed check on the Data quality card: [value] with its unit, and the FBBD [keys] of its
 * criterion. [derived] marks a value worked out by a heuristic or an estimate, not read straight
 * from the data.
 */
data class QualityRow(
    val section: String,
    val label: String,
    val value: String,
    val keys: String,
    val derived: Boolean,
)

/**
 * The Data quality card (FBBD-183): how many of the session's checks passed, failed or could not be
 * measured, with the failures listed. Every check, with how its value was found, is in the session's
 * quality.json and in the drive's log.
 */
data class QualityUi(val passed: Int, val failed: Int, val notMeasured: Int, val failures: List<QualityRow>)

/** The card's model of [report], the same on Android and iOS (FBBD-365). */
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

private const val WholeFrom = 100.0

/** [value] in [unit]: metres to the centimetre, a count whole, others to a tenth below 100. */
fun valueText(value: Double, unit: String): String {
    val number = when {
        unit == "m" -> fixed(value, 2)
        unit.isEmpty() && value % 1.0 == 0.0 -> fixed(value, 0)
        abs(value) >= WholeFrom -> fixed(value, 0)
        else -> fixed(value, 1)
    }
    return if (unit.isEmpty()) number else "$number $unit"
}
