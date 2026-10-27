@file:Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
) // Ported as written.

package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.segments.MinimumConsistencySamples
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs
import kotlin.math.max

// Port of FlappedEar Telemetry analysis/focus_areas.dart (Apache 2.0), itself a port of FlappedEar
// Overlays' FocusAreas: a short list of areas to inspect next, selected from computed observations
// only. Each area states an observation (a measured number with its metric and sample count)
// separately from a hypothesis (what might be worth comparing). A hypothesis never claims a cause
// and never recommends a driving change as safe or faster: in particular a braking spread says
// nothing about whether earlier or later braking is better. Each area names the pair of laps to
// compare as its evidence.

const val FocusAreasAlgorithm = "focus-areas-v1"

// Thresholds below which an observation is not worth a focus area.
const val FocusMinimumSectorGapSeconds = 0.05
const val FocusMinimumLossSeconds = 0.05
const val FocusMinimumBrakingSpreadMeters = 10.0

/** Of the median minimum speed. */
const val FocusMinimumSpeedSpreadFraction = 0.05

private const val TinyMedian = 1e-9

/** The best lap against the fastest recorded time through one sector. */
data class FocusSectorGap(
    val segmentId: String,
    val name: String,
    /** The best lap's sector time minus the fastest recorded. */
    val gapSeconds: Double,
    val bestLap: Any?,
    val bestLapLabel: String,
    val sourceLap: Any?,
    val sourceLapLabel: String,
)

/** One lap's loss through one window against the reference lap. */
data class FocusLoss(val segmentId: String, val name: String, val lossSeconds: Double, val lap: Any?)

/** One corner's observations on every lap. */
data class FocusCorner(
    val segmentId: String,
    val name: String,
    val observations: List<CornerLapObservation> = emptyList(),
)

/** What the focus areas are selected from. */
data class FocusInputs(
    /** The best lap. */
    val referenceLap: Any? = null,
    val referenceLabel: String = "",
    /** Laps the losses were observed on. */
    val comparedLapCount: Int = 0,
    val gaps: List<FocusSectorGap> = emptyList(),
    val losses: List<FocusLoss> = emptyList(),
    val corners: List<FocusCorner> = emptyList(),
    val speedUnit: String = "",
)

/** What a focus area is about. */
enum class FocusAreaKind(val code: String) {
    SectorGap("sectorGap"),
    RepeatedLoss("repeatedLoss"),
    BrakingSpread("brakingSpread"),
    MinimumSpeedSpread("minimumSpeedSpread"),
}

/** One area to inspect next. */
data class FocusArea(
    val kind: FocusAreaKind,
    val segmentId: String,
    val name: String,
    /** Measured, with numbers. */
    val observation: String,
    /** What may be worth comparing; never causal. */
    val hypothesis: String,
    /** The measure behind [value], e.g. `sectorGapSeconds`. */
    val metric: String,
    val value: Double,
    val unit: String,
    val sampleCount: Int,
    /** Evidence: compare [lap] against [against] through the segment. */
    val lap: Any?,
    val against: Any?,
    /** Orders the areas of one kind. */
    val score: Double,
)

private const val SecondsDecimals = 3

private fun seconds(value: Double) = fixed(value, SecondsDecimals)

private fun oneDecimal(value: Double) = fixed(value, 1)

private fun sectorGapAreas(inputs: FocusInputs) = inputs.gaps
    .filter { it.gapSeconds.isFinite() && it.gapSeconds >= FocusMinimumSectorGapSeconds }
    .map { gap ->
        FocusArea(
            kind = FocusAreaKind.SectorGap,
            segmentId = gap.segmentId,
            name = gap.name,
            metric = "sectorGapSeconds",
            value = gap.gapSeconds,
            unit = "s",
            sampleCount = 2,
            observation = "Your best lap (${gap.bestLapLabel}) was ${seconds(gap.gapSeconds)} s slower through " +
                "${gap.name} than ${gap.sourceLapLabel}, the fastest recorded there.",
            hypothesis = "Comparing the two laps through ${gap.name} may show where the time went: where " +
                "braking starts, the lowest speed, and when the throttle comes back.",
            lap = gap.bestLap,
            against = gap.sourceLap,
            score = gap.gapSeconds,
        )
    }

private fun repeatedLossAreas(inputs: FocusInputs): List<FocusArea> {
    val areas = mutableListOf<FocusArea>()
    val bySegment = LinkedHashMap<String, MutableList<FocusLoss>>()
    for (loss in inputs.losses) {
        if (!loss.lossSeconds.isFinite() || loss.lossSeconds < FocusMinimumLossSeconds) continue
        bySegment.getOrPut(loss.segmentId) { mutableListOf() } += loss
    }
    for ((segmentId, segmentLosses) in bySegment) {
        if (segmentLosses.size < MinimumConsistencySamples) continue // a pattern needs repeats
        val losses = segmentLosses.sortedBy { it.lossSeconds } // stable
        val summary = summarizeConsistency(losses.map { it.lossSeconds })
        if (!summary.available) continue
        val median = summary.median!!
        // The lap closest to the median is the typical example.
        var typical = losses.first()
        for (loss in losses) {
            if (abs(loss.lossSeconds - median) < abs(typical.lossSeconds - median)) typical = loss
        }
        val name = losses.first().name
        areas += FocusArea(
            kind = FocusAreaKind.RepeatedLoss,
            segmentId = segmentId,
            name = name,
            metric = "medianLossSeconds",
            value = median,
            unit = "s",
            sampleCount = losses.size,
            observation = "In ${losses.size} of ${max(inputs.comparedLapCount, losses.size)} compared " +
                "laps you lost time through $name against ${inputs.referenceLabel} " +
                "(median ${seconds(median)} s).",
            hypothesis = "Because it repeats, comparing a typical lap with ${inputs.referenceLabel} through " +
                "$name may show a pattern rather than a one-off.",
            lap = typical.lap,
            against = inputs.referenceLap,
            score = median * losses.size,
        )
    }
    return areas
}

private fun variabilityAreas(inputs: FocusInputs, braking: Boolean): List<FocusArea> {
    val areas = mutableListOf<FocusArea>()
    for (corner in inputs.corners) {
        val values = mutableListOf<Pair<Double, CornerLapObservation>>()
        for (observation in corner.observations) {
            // Only the measured braking point: an inferred one is not mixed in.
            val point = observation.brakingPointMeters
            if (braking && point != null && observation.brakingProvenance == "measured") {
                values += point to observation
            }
            val minimum = observation.minimumSpeed
            if (!braking && minimum != null) values += minimum to observation
        }
        // Speeds in different units are not one spread.
        if (!braking && values.any { !sameSpeedUnit(it.second.speedUnit, values.first().second.speedUnit) }) {
            continue
        }
        val numbers = values.map { it.first }
        val summary = summarizeConsistency(numbers)
        if (!summary.available) continue
        val spread = summary.interquartileRange!!
        val median = summary.median!!
        val small = if (braking) {
            spread < FocusMinimumBrakingSpreadMeters
        } else {
            spread < FocusMinimumSpeedSpreadFraction * abs(median)
        }
        if (small) continue
        val sorted = values.sortedBy { it.first } // stable
        val lap = sorted.first().second.lapReference
        val against = sorted.last().second.lapReference
        val name = corner.name
        val spreadText = oneDecimal(spread)
        areas += if (braking) {
            FocusArea(
                kind = FocusAreaKind.BrakingSpread,
                segmentId = corner.segmentId,
                name = name,
                metric = "brakingPointInterquartileRangeMeters",
                value = spread,
                unit = "m",
                sampleCount = numbers.size,
                observation = "Where braking starts for $name varies by $spreadText m across the middle half " +
                    "of ${numbers.size} laps (measured from the brake signal).",
                hypothesis = "A more repeatable braking reference for $name may be worth checking. This does " +
                    "not show whether earlier or later braking is faster or safe; compare the earliest " +
                    "and the latest example.",
                lap = lap,
                against = against,
                score = spread,
            )
        } else {
            val unit = if (inputs.speedUnit.isEmpty()) "" else " ${inputs.speedUnit}"
            val ownUnits = if (inputs.speedUnit.isEmpty()) " Speeds are in the recording's own units." else ""
            FocusArea(
                kind = FocusAreaKind.MinimumSpeedSpread,
                segmentId = corner.segmentId,
                name = name,
                metric = "minimumSpeedInterquartileRange",
                value = spread,
                unit = inputs.speedUnit,
                sampleCount = numbers.size,
                observation = "The lowest speed through $name varies by $spreadText$unit across the middle half " +
                    "of ${numbers.size} laps (median ${oneDecimal(median)}$unit).$ownUnits",
                hypothesis = "Comparing the slowest and the fastest example through $name may show what " +
                    "differs; a higher minimum speed is not by itself better.",
                lap = lap,
                against = against,
                score = spread / max(TinyMedian, abs(median)),
            )
        }
    }
    return areas
}

/**
 * At most [maximum] areas, at most one per segment. The first round takes the strongest area of
 * each kind in the order sector gap, repeated loss, braking spread, minimum-speed spread; remaining
 * places are filled round-robin in the same kind order, each kind by score. Deterministic for equal
 * scores (segment id).
 */
fun selectFocusAreas(inputs: FocusInputs, maximum: Int = 3): List<FocusArea> {
    val kinds = listOf(
        sectorGapAreas(inputs),
        repeatedLossAreas(inputs),
        variabilityAreas(inputs, braking = true),
        variabilityAreas(inputs, braking = false),
    ).map { areas -> areas.sortedWith(compareByDescending<FocusArea> { it.score }.thenBy { it.segmentId }) }
    val selected = mutableListOf<FocusArea>()
    val segments = HashSet<String>()
    val next = IntArray(kinds.size)
    // Round one: the strongest of each kind; then fill in kind order.
    for (round in 0 until 2) {
        if (selected.size >= maximum) break
        var progress = true
        while (progress && selected.size < maximum) {
            progress = false
            for (kind in kinds.indices) {
                if (selected.size >= maximum) break
                while (next[kind] < kinds[kind].size && kinds[kind][next[kind]].segmentId in segments) next[kind]++
                if (next[kind] >= kinds[kind].size) continue
                val area = kinds[kind][next[kind]]
                selected += area
                segments += area.segmentId
                next[kind]++
                progress = round == 1 // round one takes one of each kind only
            }
            if (round == 0) break
        }
    }
    return selected
}
