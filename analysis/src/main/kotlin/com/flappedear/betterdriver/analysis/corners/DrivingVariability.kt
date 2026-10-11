@file:Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod") // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.MinimumConsistencySamples
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import kotlin.math.sqrt

// Port of FlappedEar Telemetry analysis/driving_variability.dart (Apache 2.0), from FlappedEar
// Overlays' DrivingVariability: braking, apex, exit and line variability for one corner across a
// population of laps, each metric summarized with the consistency statistics. Measured and
// inferred braking points and pickups are never mixed. The line spread is the lateral offset from
// the reference line at the apex, reported next to the recording's typical GPS accuracy.

const val DrivingVariabilityAlgorithm = "driving-variability-v1"

/** One lap's metrics of one corner. */
data class CornerLapObservation(
    val lapReference: Any? = null,
    val brakingPointMeters: Double? = null,
    /** `measured` or `inferred`. */
    val brakingProvenance: String = "",
    val apexSpeed: Double? = null,
    val minimumSpeed: Double? = null,
    val exitSpeed: Double? = null,
    /** The unit of the speeds. */
    val speedUnit: String = "",
    val pickupMeters: Double? = null,
    /** `measured` or `inferred`. */
    val pickupProvenance: String = "",
    /** Positive left of the reference line. */
    val lineOffsetMeters: Double? = null,
    /** The same at the corner's start and end (FlappedEar Telemetry only). */
    val entryLineOffsetMeters: Double? = null,
    val exitLineOffsetMeters: Double? = null,
    /** The recording's own accuracy at the apex. */
    val gpsAccuracyMeters: Double? = null,
)

/** How repeatable one corner is. */
data class CornerVariability(
    val segmentId: String = "",
    val name: String = "",
    val brakingPointMeasured: ConsistencySummary = ConsistencySummary(),
    val brakingPointInferred: ConsistencySummary = ConsistencySummary(),
    val apexSpeed: ConsistencySummary = ConsistencySummary(),
    val minimumSpeed: ConsistencySummary = ConsistencySummary(),
    val exitSpeed: ConsistencySummary = ConsistencySummary(),
    val pickupMeasured: ConsistencySummary = ConsistencySummary(),
    val pickupInferred: ConsistencySummary = ConsistencySummary(),
    val lineOffset: ConsistencySummary = ConsistencySummary(),
    /** Median accuracy of the observations. */
    val typicalGpsAccuracyMeters: Double? = null,
    /** The line spread is larger than the typical GPS accuracy. */
    val lineSpreadResolvable: Boolean = false,
    val entryLineOffset: ConsistencySummary = ConsistencySummary(),
    val exitLineOffset: ConsistencySummary = ConsistencySummary(),
) {
    val entryLineResolvable: Boolean get() = resolvable(entryLineOffset)
    val exitLineResolvable: Boolean get() = resolvable(exitLineOffset)

    private fun resolvable(line: ConsistencySummary): Boolean {
        val accuracy = typicalGpsAccuracyMeters ?: return false
        return line.available && line.interquartileRange!! > accuracy
    }
}

/** The variability of one corner over [observations]. */
fun summarizeCornerVariability(
    segmentId: String,
    name: String,
    observations: List<CornerLapObservation>,
    minimumSamples: Int = MinimumConsistencySamples,
): CornerVariability {
    // Speeds are pooled only in one unit; a corner whose laps are in different units has no spread.
    var speedUnit: String? = null
    var mixedUnits = false
    for (lap in observations) {
        if (lap.apexSpeed == null && lap.minimumSpeed == null && lap.exitSpeed == null) continue
        val unit = speedUnit ?: lap.speedUnit.also { speedUnit = it }
        if (!sameSpeedUnit(unit, lap.speedUnit)) mixedUnits = true
    }
    fun summary(values: List<Double>) = summarizeConsistency(values, minimumSamples)
    fun braking(provenance: String) =
        observations.filter { it.brakingProvenance == provenance }.mapNotNull { it.brakingPointMeters }
    fun pickup(provenance: String) =
        observations.filter { it.pickupProvenance == provenance }.mapNotNull { it.pickupMeters }
    fun speeds(read: (CornerLapObservation) -> Double?) = if (mixedUnits) emptyList() else observations.mapNotNull(read)
    val lineOffset = summary(observations.mapNotNull { it.lineOffsetMeters })
    val accuracy = observations.mapNotNull { it.gpsAccuracyMeters }.filter { it.isFinite() && it >= 0.0 }
    val typical = summarizeConsistency(accuracy, minimumSamples = 1)
    val typicalAccuracy = if (typical.available) typical.median else null
    return CornerVariability(
        segmentId = segmentId,
        name = name,
        brakingPointMeasured = summary(braking("measured")),
        brakingPointInferred = summary(braking("inferred")),
        apexSpeed = summary(speeds { it.apexSpeed }),
        minimumSpeed = summary(speeds { it.minimumSpeed }),
        exitSpeed = summary(speeds { it.exitSpeed }),
        pickupMeasured = summary(pickup("measured")),
        pickupInferred = summary(pickup("inferred")),
        lineOffset = lineOffset,
        typicalGpsAccuracyMeters = typicalAccuracy,
        // Without a stated accuracy the spread is reported but never claimed resolvable.
        lineSpreadResolvable = lineOffset.available && typicalAccuracy != null &&
            lineOffset.interquartileRange!! > typicalAccuracy,
        entryLineOffset = summary(observations.mapNotNull { it.entryLineOffsetMeters }),
        exitLineOffset = summary(observations.mapNotNull { it.exitLineOffsetMeters }),
    )
}

/**
 * Signed lateral distance (metres, positive left of travel) of [localPoint] from [axis] at
 * [progressMeters]; null outside the axis.
 */
fun lateralOffsetMeters(axis: ProgressAxis, progressMeters: Double, localPoint: MetricPoint): Double? {
    if (!axis.valid || axis.points.size < 2 || axis.cumulative.size != axis.points.size ||
        !progressMeters.isFinite() || progressMeters < 0.0 || progressMeters > axis.lengthMeters
    ) {
        return null
    }
    var upper = 0
    var high = axis.cumulative.size
    while (upper < high) {
        val middle = (upper + high) shr 1
        if (axis.cumulative[middle] < progressMeters) upper = middle + 1 else high = middle
    }
    val index = upper.coerceIn(1, axis.points.size - 1)
    val a = axis.points[index - 1]
    val b = axis.points[index]
    val span = axis.cumulative[index] - axis.cumulative[index - 1]
    val fraction = if (span > 0.0) ((progressMeters - axis.cumulative[index - 1]) / span).coerceIn(0.0, 1.0) else 0.0
    val onEast = a.eastMeters + (b.eastMeters - a.eastMeters) * fraction
    val onNorth = a.northMeters + (b.northMeters - a.northMeters) * fraction
    val directionEast = b.eastMeters - a.eastMeters
    val directionNorth = b.northMeters - a.northMeters
    val length = sqrt(directionEast * directionEast + directionNorth * directionNorth)
    if (length <= 0.0) return null
    val offsetEast = localPoint.eastMeters - onEast
    val offsetNorth = localPoint.northMeters - onNorth
    return (directionEast * offsetNorth - directionNorth * offsetEast) / length
}
