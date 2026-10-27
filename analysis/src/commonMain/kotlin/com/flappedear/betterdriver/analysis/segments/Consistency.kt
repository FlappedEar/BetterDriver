@file:Suppress("MagicNumber") // Quartile fractions.

package com.flappedear.betterdriver.analysis.segments

import kotlin.math.ceil
import kotlin.math.floor

// Port of FlappedEar Telemetry analysis/consistency.dart and the theoretical-best summary of
// outing_theoretical_best.dart (Apache 2.0), from FlappedEar Overlays: how repeatable lap and
// sector times are, and the theoretical best as the screens show it. "Typical" is the median; the
// spread is the interquartile range, the time between the 25th and 75th percentile, so a warm-up
// or traffic lap does not dominate it. Quantiles interpolate linearly between ordered samples.
// The summary's track map and corner variability are not ported.

const val ConsistencyAlgorithm = "consistency-iqr-v1"

/** Fewer samples than this give no statistics. */
const val MinimumConsistencySamples = 3
const val ConsistencyTooFewSamples = "tooFewSamples"

/** Quartiles of a set of times, or why there are none. */
data class ConsistencySummary(
    /** Finite samples considered. */
    val count: Int = 0,
    val minimum: Double? = null,
    val q1: Double? = null,
    val median: Double? = null,
    val q3: Double? = null,
    val maximum: Double? = null,
    /** [q3] − [q1], seconds. */
    val interquartileRange: Double? = null,
    /** Set when [count] is below the minimum. */
    val unavailableReason: String = "",
    val available: Boolean = false,
)

/** Quartiles of the finite [values], unavailable below [minimumSamples]. */
fun summarizeConsistency(
    values: Iterable<Double>,
    minimumSamples: Int = MinimumConsistencySamples,
): ConsistencySummary {
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.size < maxOf(1, minimumSamples)) {
        return ConsistencySummary(count = sorted.size, unavailableReason = ConsistencyTooFewSamples)
    }
    fun quantile(fraction: Double): Double {
        val position = fraction * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }
    val q1 = quantile(0.25)
    val q3 = quantile(0.75)
    return ConsistencySummary(
        count = sorted.size,
        minimum = sorted.first(),
        q1 = q1,
        median = quantile(0.5),
        q3 = q3,
        maximum = sorted.last(),
        interquartileRange = q3 - q1,
        available = true,
    )
}

/** One approved segment's consistency across a population. */
data class SectorConsistency(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    val summary: ConsistencySummary,
    /** Laps with a time in this segment. */
    val lapReferences: List<Any?> = emptyList(),
)

/**
 * Each approved segment's consistency across [population], laps timed on one shared axis; laps of
 * another revision are ignored.
 */
fun computeSectorConsistency(
    approved: ApprovedSegments,
    population: List<LapSectorTimes>,
    minimumSamples: Int = MinimumConsistencySamples,
): List<SectorConsistency> {
    if (approved.revision.isEmpty()) return emptyList()
    return approved.segments.map { segment ->
        val times = mutableListOf<Double>()
        val laps = mutableListOf<Any?>()
        for (lap in population) {
            if (!lap.valid ||
                lap.stamp.revision != approved.revision ||
                lap.stamp.trackConfigurationReference != approved.trackConfigurationReference
            ) {
                continue
            }
            for (candidate in lap.sectors) {
                val seconds = candidate.seconds
                if (candidate.segmentId != segment.id || seconds == null) continue
                times += seconds
                laps += lap.lapReference
            }
        }
        SectorConsistency(segment.id, segment.name, segment.type, summarizeConsistency(times, minimumSamples), laps)
    }
}

/** Readable text for a theoretical-best unavailability reason. */
fun theoreticalBestReasonText(reason: String) = when (reason) {
    TheoreticalBestNoApprovedSegmentation -> "No approved segments to measure sectors against."
    TheoreticalBestIncompleteCoverage ->
        "At least one sector has no fully covered time on any eligible lap, so no total is shown."
    else -> reason
}

/** One approved segment in the published theoretical best. */
data class TheoreticalBestRow(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    val startProgressMeters: Double,
    val endProgressMeters: Double,
    /** The fastest time through this segment. */
    val seconds: Double? = null,
    /** The lap that recorded [seconds]. */
    val sourceLapReference: Any? = null,
    /** Readable, when [seconds] is null. */
    val unavailableReason: String = "",
    /** The best lap's time through this segment. */
    val actualSeconds: Double? = null,
    /** [actualSeconds] − [seconds]: what the best lap leaves here. */
    val lossSeconds: Double? = null,
    /** How repeatable this segment is across the population. */
    val consistency: ConsistencySummary = ConsistencySummary(),
)

/** The best lap in the published theoretical best. */
data class TheoreticalBestActual(
    val reference: Any?,
    val lapSeconds: Double,
    /** The approved segments tile the whole lap. */
    val coversWholeLap: Boolean,
    /** Its own times summed over the same segments, when every one is timed. */
    val sectorSumSeconds: Double? = null,
) {
    /** The lap time when the segments cover the whole lap, otherwise the sum over them. */
    val shownSeconds: Double? get() = if (coversWholeLap) lapSeconds else sectorSumSeconds
}

/** The theoretical best as the screens show it (Overlays' publishTheoreticalBest). */
data class TheoreticalBestSummary(
    /** In approved order. */
    val sectors: List<TheoreticalBestRow>,
    /** [sectors] by what the best lap loses there, largest first; without a loss last, in order. */
    val gains: List<TheoreticalBestRow>,
    val totalSeconds: Double? = null,
    val actualBest: TheoreticalBestActual? = null,
    /** The time available: the best lap's sector sum minus [totalSeconds]. */
    val differenceSeconds: Double? = null,
    val revision: String = "",
    val trackConfigurationReference: String = "",
)

/**
 * The published form of [best], computed over [population] against [approved], with [actualBest]
 * (the best lap timed on the same axis) for the per-segment losses.
 */
fun publishTheoreticalBest(
    approved: ApprovedSegments,
    best: TheoreticalBestLap,
    population: List<LapSectorTimes>,
    actualBest: LapSectorTimes?,
): TheoreticalBestSummary {
    val bounds = approved.segments.associate { it.id to (it.startProgressMeters to it.endProgressMeters) }
    val consistency = computeSectorConsistency(approved, population)
    var actualSum = 0.0
    var actualComplete = actualBest != null
    val rows = best.sectors.map { sector ->
        val actualSeconds = actualBest?.sector(sector.segmentId)?.seconds
        if (actualSeconds != null) actualSum += actualSeconds else actualComplete = false
        val (start, end) = bounds[sector.segmentId] ?: (0.0 to 0.0)
        val seconds = sector.seconds
        TheoreticalBestRow(
            segmentId = sector.segmentId,
            name = sector.name,
            type = sector.type,
            startProgressMeters = start,
            endProgressMeters = end,
            seconds = seconds,
            sourceLapReference = if (seconds == null) null else sector.sourceLapReference,
            unavailableReason = if (seconds == null) theoreticalBestReasonText(sector.unavailableReason) else "",
            actualSeconds = actualSeconds,
            lossSeconds = if (actualSeconds != null && seconds != null) actualSeconds - seconds else null,
            consistency = consistency.lastOrNull { it.segmentId == sector.segmentId }?.summary
                ?: ConsistencySummary(),
        )
    }
    // Where the best lap loses time, largest first (stable).
    val gains = rows.indices.sortedWith(
        compareByDescending<Int> { rows[it].lossSeconds ?: -1.0 }.thenBy { it },
    ).map { rows[it] }
    val total = best.totalSeconds
    return TheoreticalBestSummary(
        sectors = rows,
        gains = gains,
        totalSeconds = total,
        actualBest = actualBest?.let {
            TheoreticalBestActual(
                it.lapReference, it.lapSeconds, it.completePartition, if (actualComplete) actualSum else null,
            )
        },
        differenceSeconds = if (actualBest != null && actualComplete && total != null) actualSum - total else null,
        revision = best.stamp.revision,
        trackConfigurationReference = best.stamp.trackConfigurationReference,
    )
}
