package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerVariability
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import java.util.Locale

/** What a corner detail measures, which decides how it is written. */
enum class CornerMeasure { Meters, Speed, G }

/** One measure of one corner on the best lap and the compared lap, and its spread over the counted laps. */
data class CornerMetricRow(
    val label: String,
    val measure: CornerMeasure,
    val best: Double?,
    val lap: Double?,
    /** Interquartile range over the counted laps; null with too few. */
    val spread: Double?,
    /** The value is inferred (from deceleration or acceleration), not read from a pedal. */
    val inferred: Boolean = false,
)

/** The braking, speeds, pickup and line of one corner (UI-68). */
data class CornerDetail(
    val name: String,
    val rows: List<CornerMetricRow>,
    /** Unit of the speed rows. */
    val speedUnit: String,
    /** The line's spread is no larger than the GPS accuracy, so it says little. */
    val lineWithinGps: Boolean,
    val lineSpreadMeters: Double?,
)

/**
 * The corner details of [analysis]: for every corner, the fastest lap ([bestIndex]) against lap
 * [otherIndex] (both in timed-lap order), with the spread over the counted laps.
 */
fun cornerDetails(analysis: SessionAnalysis, bestIndex: Int, otherIndex: Int?): List<CornerDetail> {
    val segments = analysis.segments?.segments.orEmpty()
    return segments.mapNotNull { segment ->
        val laps = analysis.cornerMetrics[segment.id] ?: return@mapNotNull null
        val best = laps.getOrNull(bestIndex) ?: return@mapNotNull null
        val other = otherIndex?.let(laps::getOrNull)
        val variability = analysis.variability[segment.id]
        CornerDetail(
            name = segment.name,
            rows = rows(segment.startProgressMeters, analysis.axis?.lengthMeters ?: 0.0, best, other, variability),
            speedUnit = best.speeds.unit,
            lineWithinGps = variability != null && variability.lineOffset.available &&
                !variability.lineSpreadResolvable,
            lineSpreadMeters = variability?.lineOffset?.interquartileRange,
        )
    }
}

private fun rows(
    entry: Double,
    length: Double,
    best: CornerLapMetrics,
    other: CornerLapMetrics?,
    variability: CornerVariability?,
): List<CornerMetricRow> {
    val inferredBraking = best.braking.provenance == "inferred"
    val inferredPickup = best.exit.pickup.provenance == "inferred"
    // The pickup as metres after the corner entry, along the lap.
    fun pickup(m: CornerLapMetrics) = m.exit.pickup.progressMeters?.let {
        if (it >= entry || length <= 0.0) it - entry else it + length - entry
    }
    return listOf(
        CornerMetricRow(
            "Brakes",
            CornerMeasure.Meters,
            best.braking.distanceBeforeEntryMeters,
            other?.braking?.distanceBeforeEntryMeters,
            spread(if (inferredBraking) variability?.brakingPointInferred else variability?.brakingPointMeasured),
            inferredBraking,
        ),
        CornerMetricRow(
            "Peak braking",
            CornerMeasure.G,
            best.braking.peakDeceleration,
            other?.braking?.peakDeceleration,
            null,
        ),
        CornerMetricRow(
            "Min speed",
            CornerMeasure.Speed,
            best.speeds.minimum.value,
            other?.speeds?.minimum?.value,
            spread(variability?.minimumSpeed),
        ),
        CornerMetricRow(
            "Exit speed",
            CornerMeasure.Speed,
            best.speeds.exit.value,
            other?.speeds?.exit?.value,
            spread(variability?.exitSpeed),
        ),
        CornerMetricRow(
            "Throttle",
            CornerMeasure.Meters,
            pickup(best),
            other?.let(::pickup),
            spread(if (inferredPickup) variability?.pickupInferred else variability?.pickupMeasured),
            inferredPickup,
        ),
    )
}

private fun spread(summary: ConsistencySummary?) = summary?.takeIf { it.available }?.interquartileRange

/** [value] in its measure, with [speedUnit] for speeds; a dash when missing. */
internal fun formatMeasure(value: Double?, measure: CornerMeasure, speedUnit: String): String = when {
    value == null -> "–"
    measure == CornerMeasure.Meters -> String.format(Locale.ROOT, "%.0f m", value)
    measure == CornerMeasure.G -> String.format(Locale.ROOT, "%.2f g", value)
    speedUnit.isEmpty() -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.1f %s", value, speedUnit)
}
