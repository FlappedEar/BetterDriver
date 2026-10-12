package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.AnalysisProvenance
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.measureConfidence
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerVariability
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.recorder.Provenance
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
    /**
     * Where the values come from (FBBD-174): inferred for a braking point or pickup found from
     * acceleration, not a pedal; else the provenance of the channel read. Null when not known.
     */
    val provenance: Provenance? = null,
    /**
     * How far the best lap's value ([confidence]) and the compared lap's ([lapConfidence]) can be
     * trusted (FBBD-177), for a braking point or a minimum speed; null for the other rows. A Low
     * value is not shown.
     */
    val confidence: ConfidenceUi? = null,
    val lapConfidence: ConfidenceUi? = null,
) {
    /** The row's values carry [NotMeasuredMark]. */
    val marked: Boolean get() = provenance.notMeasured

    /** The lower of the two values' confidence, shown next to the row; null for a row without one. */
    val shownConfidence: ConfidenceUi?
        get() = listOfNotNull(confidence, lapConfidence).minByOrNull { it.level }
}

/** The braking, speeds, pickup and line of one corner (UI-68). */
data class CornerDetail(
    val name: String,
    val rows: List<CornerMetricRow>,
    /** Unit of the speed rows. */
    val speedUnit: String,
    /** The line's spread is no larger than the GPS accuracy, so it says little. */
    val lineWithinGps: Boolean,
    val lineSpreadMeters: Double?,
    /** The corner's segment id. */
    val id: String = "",
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
            rows = rows(
                Lap(segment.startProgressMeters, analysis.axis?.lengthMeters ?: 0.0),
                best,
                other,
                variability,
                analysis.provenance,
            ).map { row ->
                // How far a braking point or a minimum speed of each lap can be trusted (FBBD-177).
                fun graded(index: Int?) = index?.takeIf { row.label in GradedRows }
                    ?.let { analysis.measureConfidence(segment.id, it, row.provenance).ui() }
                row.copy(confidence = graded(bestIndex), lapConfidence = graded(otherIndex?.takeIf { other != null }))
            },
            speedUnit = best.speeds.unit,
            // Only with a recorded accuracy: without one the spread is neither within nor beyond it.
            lineWithinGps = variability != null && variability.lineOffset.available &&
                variability.typicalGpsAccuracyMeters != null && !variability.lineSpreadResolvable,
            lineSpreadMeters = variability?.lineOffset?.interquartileRange,
            id = segment.id,
        )
    }
}

/** The rows whose values are graded (FBBD-177): what a coach would advise from. */
private val GradedRows = setOf("Brakes", "Min speed")

/** Where a corner starts on the lap ([entry]) and the lap's [length], metres. */
private class Lap(val entry: Double, val length: Double)

private fun rows(
    lap: Lap,
    best: CornerLapMetrics,
    other: CornerLapMetrics?,
    variability: CornerVariability?,
    sources: AnalysisProvenance,
): List<CornerMetricRow> {
    val inferredBraking = best.braking.provenance == "inferred"
    val inferredPickup = best.exit.pickup.provenance == "inferred"
    // The pickup as metres after the corner entry, along the lap.
    fun pickup(m: CornerLapMetrics) = m.exit.pickup.progressMeters?.let {
        if (it >= lap.entry || lap.length <= 0.0) it - lap.entry else it + lap.length - lap.entry
    }
    return listOf(
        CornerMetricRow(
            "Brakes",
            CornerMeasure.Meters,
            best.braking.distanceBeforeEntryMeters,
            other?.braking?.distanceBeforeEntryMeters,
            spread(if (inferredBraking) variability?.brakingPointInferred else variability?.brakingPointMeasured),
            sources.braking(best.braking.provenance),
        ),
        CornerMetricRow(
            "Peak braking",
            CornerMeasure.G,
            best.braking.peakDeceleration,
            other?.braking?.peakDeceleration,
            null,
            sources.acceleration,
        ),
        CornerMetricRow(
            "Min speed",
            CornerMeasure.Speed,
            best.speeds.minimum.value,
            other?.speeds?.minimum?.value,
            spread(variability?.minimumSpeed),
            sources.speed,
        ),
        CornerMetricRow(
            "Exit speed",
            CornerMeasure.Speed,
            best.speeds.exit.value,
            other?.speeds?.exit?.value,
            spread(variability?.exitSpeed),
            sources.speed,
        ),
        CornerMetricRow(
            "Throttle",
            CornerMeasure.Meters,
            pickup(best),
            other?.let(::pickup),
            spread(if (inferredPickup) variability?.pickupInferred else variability?.pickupMeasured),
            sources.pickup(best.exit.pickup.provenance),
        ),
    )
}

private fun spread(summary: ConsistencySummary?) = summary?.takeIf { it.available }?.interquartileRange

/** [value] in its measure, with [speedUnit] for speeds; a dash when missing. */
internal fun formatMeasure(value: Double?, measure: CornerMeasure, speedUnit: String): String = when {
    value == null -> Missing
    measure == CornerMeasure.Meters -> String.format(Locale.ROOT, "%.0f m", value)
    measure == CornerMeasure.G -> String.format(Locale.ROOT, "%.2f g", value)
    speedUnit.isEmpty() -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.1f %s", value, speedUnit)
}
