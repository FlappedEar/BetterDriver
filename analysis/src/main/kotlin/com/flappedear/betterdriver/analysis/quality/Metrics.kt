package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.math.ceil

// Small helpers shared by the report's sections (FBBD-183).

internal const val MicrosPerSecond = 1_000_000L
internal const val MicrosPerMilli = 1_000L
internal const val Percent = 100.0

/** A value with a pass criterion: PASS when [ok]. */
@Suppress("LongParameterList") // Each is a field of the metric.
internal fun judged(
    id: String,
    label: String,
    value: Double,
    unit: String,
    ok: Boolean,
    basis: Basis,
    criterion: String,
    how: String,
) = QualityMetric(id, label, value, unit, if (ok) Verdict.PASS else Verdict.FAIL, basis, criterion, how)

/** A value shown for information. */
@Suppress("LongParameterList") // Each is a field of the metric.
internal fun info(id: String, label: String, value: Double?, unit: String, basis: Basis, how: String) =
    QualityMetric(id, label, value, unit, Verdict.INFO, if (value == null) Basis.NONE else basis, null, how)

/** A criterion this session cannot be checked against, and [why]. */
internal fun notMeasured(id: String, label: String, criterion: String?, why: String) =
    QualityMetric(id, label, null, "", Verdict.NOT_MEASURED, Basis.NONE, criterion, why)

/** The [p] quantile (0 to 1) of [values] by nearest rank; null when empty. */
internal fun nearestRank(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
}

/** The `key=value` fields of an event text; a value ends at the next space. */
internal fun fields(text: String): Map<String, String> = text.split(' ')
    .mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }
    .toMap()

internal fun List<RecorderEvent>.ofKind(kind: String) = filter { it.kind == kind }

internal fun millis(micros: Long): Double = micros.toDouble() / MicrosPerMilli

/** [micros] in whole milliseconds, for text. */
internal fun ms(micros: Long): Long = Math.round(micros.toDouble() / MicrosPerMilli)

internal fun seconds(micros: Long): Double = micros.toDouble() / MicrosPerSecond
