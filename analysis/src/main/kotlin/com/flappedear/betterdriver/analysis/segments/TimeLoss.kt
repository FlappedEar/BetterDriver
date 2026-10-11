@file:Suppress(
    "ReturnCount",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
) // Ported as written.

package com.flappedear.betterdriver.analysis.segments

import kotlin.math.abs

// Port of FlappedEar Telemetry analysis/time_loss.dart and rankOutingTimeLosses of
// outing_theoretical_best.dart (Apache 2.0), itself a port of FlappedEar Overlays' TimeLoss:
// time-loss windows between two laps, one per approved segment, and the largest losses against a
// reference lap.
//
// Over a window the A-minus-B delta changes by exactly A's time through the window minus B's: the
// window's increment. Positive means A lost time there. The running delta at the window's entry
// and exit is reported separately: "A is 1.2 s behind here" is not "A lost 1.2 s here".

/** The algorithm a [TimeLossObservations] result is stamped with. */
const val TimeLossAlgorithm = "time-loss-windows-v1"

/** Why a window has no increment: either lap has no complete time through it. */
const val TimeLossUntimed = "untimed"

/** Two window ends closer than this are the same boundary. */
const val TimeLossAdjacencyMeters = 0.5

/** Why two laps cannot be compared. */
const val TimeLossDifferentSegmentOrRevision = "differentSegmentOrRevision"

/** Why there is no ranking: no reference lap. */
const val TimeLossNoReference = "noReferenceLap"

/** No approved segments to compare against. */
const val TimeLossNoApprovedSegmentation = "noApprovedSegmentation"

/** A window's role. */
enum class TimeLossRole(val jsonName: String) {
    Corner("corner"),

    /** A straight right after a corner, so time lost on the exit is not counted in the corner. */
    Continuation("continuation"),
    Straight("straight"),
    Sector("sector"),
}

/** One approved segment as a loss window between laps A and B. */
data class TimeLossWindow(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    val role: TimeLossRole,
    /** For a continuation: the corner it follows. */
    val cornerSegmentId: String = "",
    val startProgressMeters: Double,
    val endProgressMeters: Double,
    /** A minus B through this window. */
    val incrementSeconds: Double? = null,
    /** Running A-minus-B delta at entry. */
    val cumulativeAtStartSeconds: Double? = null,
    /** Running A-minus-B delta at exit. */
    val cumulativeAtEndSeconds: Double? = null,
    /** Set when [incrementSeconds] is null. */
    val unavailableReason: String = "",
)

/** Every window between two laps. */
data class TimeLossObservations(
    val stamp: SegmentationStamp = SegmentationStamp(),
    /** In track order. */
    val windows: List<TimeLossWindow> = emptyList(),
    /** Sum of every timed increment; the lap-time difference with a complete partition. */
    val timedIncrementSumSeconds: Double = 0.0,
    val allWindowsTimed: Boolean = false,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

private fun sameBoundary(first: Double, second: Double, axisLengthMeters: Double): Boolean {
    if (abs(first - second) <= TimeLossAdjacencyMeters) return true
    // A corner ending at the gate continues onto a straight starting at it.
    return axisLengthMeters > 0.0 && abs(abs(first - second) - axisLengthMeters) <= TimeLossAdjacencyMeters
}

private fun stampMatches(times: LapSectorTimes, approved: ApprovedSegments) =
    times.valid &&
        times.stamp.revision == approved.revision &&
        times.stamp.trackConfigurationReference == approved.trackConfigurationReference

private fun hasSegments(approved: ApprovedSegments) = approved.revision.isNotEmpty() && approved.segments.isNotEmpty()

/**
 * The windows between laps [a] and [b], both timed against [approved] on one shared axis of
 * [axisLengthMeters]. [lapStartA] and [lapStartB] are the laps' timed starts, used only for the
 * running deltas.
 */
fun computeTimeLossObservations(
    approved: ApprovedSegments,
    axisLengthMeters: Double,
    a: LapSectorTimes,
    lapStartA: Double,
    b: LapSectorTimes,
    lapStartB: Double,
): TimeLossObservations {
    if (!hasSegments(approved)) return TimeLossObservations(unavailableReason = TimeLossNoApprovedSegmentation)
    if (!stampMatches(a, approved) || !stampMatches(b, approved)) {
        return TimeLossObservations(unavailableReason = TimeLossDifferentSegmentOrRevision)
    }
    val ordered = a.sectors.sortedBy { it.startProgressMeters }
    fun cumulative(timeA: Double?, timeB: Double?) =
        if (timeA == null || timeB == null) null else (timeA - lapStartA) - (timeB - lapStartB)

    var allTimed = true
    var sum = 0.0
    val windows = ordered.map { sectorA ->
        var role = TimeLossRole.Sector
        var cornerId = ""
        if (sectorA.type == TrackSegmentType.Corner) {
            role = TimeLossRole.Corner
        } else if (sectorA.type == TrackSegmentType.Straight) {
            role = TimeLossRole.Straight
            val corner = ordered.firstOrNull {
                it.type == TrackSegmentType.Corner &&
                    sameBoundary(it.endProgressMeters, sectorA.startProgressMeters, axisLengthMeters)
            }
            if (corner != null) {
                role = TimeLossRole.Continuation
                cornerId = corner.segmentId
            }
        }
        val sectorB = b.sector(sectorA.segmentId)
        var atStart: Double? = null
        var atEnd: Double? = null
        var increment: Double? = null
        if (sectorB != null) {
            atStart = cumulative(sectorA.startTime, sectorB.startTime)
            atEnd = cumulative(sectorA.endTime, sectorB.endTime)
        }
        val secondsA = sectorA.seconds
        val secondsB = sectorB?.seconds
        if (secondsA != null && secondsB != null) {
            increment = secondsA - secondsB
            // A gate-crossing window ends in the next lap's opening metres; its exit is this
            // lap's running delta plus the window's increment.
            if (sectorA.endProgressMeters < sectorA.startProgressMeters && atStart != null) {
                atEnd = atStart + increment
            }
            sum += increment
        } else {
            allTimed = false
        }
        TimeLossWindow(
            segmentId = sectorA.segmentId,
            name = sectorA.name,
            type = sectorA.type,
            role = role,
            cornerSegmentId = cornerId,
            startProgressMeters = sectorA.startProgressMeters,
            endProgressMeters = sectorA.endProgressMeters,
            incrementSeconds = increment,
            cumulativeAtStartSeconds = atStart,
            cumulativeAtEndSeconds = atEnd,
            unavailableReason = if (increment == null) TimeLossUntimed else "",
        )
    }
    return TimeLossObservations(
        stamp = stampOf(approved, TimeLossAlgorithm),
        windows = windows,
        timedIncrementSumSeconds = sum,
        allWindowsTimed = allTimed,
        valid = true,
    )
}

/** A lap's sector times with its timed start. */
data class TimedLapSectors(val times: LapSectorTimes, val startTime: Double)

/** One observed loss: a window where a lap took longer than the reference. */
data class RankedTimeLoss(
    val lapReference: Any?,
    val lapStartTime: Double,
    val window: TimeLossWindow,
    val lossSeconds: Double,
    /** Covered fraction of the window, 0 to 1. */
    val coverageLap: Double,
    val coverageReference: Double,
)

/** The largest losses of a population of laps against a reference lap. */
data class TimeLossRanking(
    val stamp: SegmentationStamp = SegmentationStamp(),
    val referenceLap: Any? = null,
    /** Largest first, at most the requested count. */
    val losses: List<RankedTimeLoss> = emptyList(),
    /** Positive increments before truncation. */
    val observationCount: Int = 0,
    val comparedLapCount: Int = 0,
    val untimedWindowCount: Int = 0,
    val unavailableReason: String = "",
    val valid: Boolean = false,
)

/**
 * Every positive increment of every lap of [laps] against [reference], largest first, at most
 * [maximumResults] (all when negative). Gains and the reference lap itself are not losses. Ties
 * are broken by track position, then by lap start.
 */
fun rankTimeLosses(
    approved: ApprovedSegments,
    axisLengthMeters: Double,
    laps: List<TimedLapSectors>,
    reference: TimedLapSectors,
    maximumResults: Int = 50,
): TimeLossRanking {
    if (!reference.times.valid || reference.times.lapReference == null) {
        return TimeLossRanking(unavailableReason = TimeLossNoReference)
    }
    if (!hasSegments(approved)) return TimeLossRanking(unavailableReason = TimeLossNoApprovedSegmentation)
    fun coverage(times: LapSectorTimes, segmentId: String): Double {
        val sector = times.sector(segmentId)
        if (sector == null || sector.lengthMeters <= 0.0) return 0.0
        return (sector.coveredMeters / sector.lengthMeters).coerceIn(0.0, 1.0)
    }

    val losses = mutableListOf<RankedTimeLoss>()
    var compared = 0
    var untimed = 0
    for (lap in laps) {
        if (lap.times.lapReference == reference.times.lapReference) continue
        val observations = computeTimeLossObservations(
            approved, axisLengthMeters, lap.times, lap.startTime, reference.times, reference.startTime,
        )
        if (!observations.valid) continue
        ++compared
        for (window in observations.windows) {
            val increment = window.incrementSeconds
            if (increment == null) {
                ++untimed
                continue
            }
            if (increment <= 0.0) continue
            losses += RankedTimeLoss(
                lapReference = lap.times.lapReference,
                lapStartTime = lap.startTime,
                window = window,
                lossSeconds = increment,
                coverageLap = coverage(lap.times, window.segmentId),
                coverageReference = coverage(reference.times, window.segmentId),
            )
        }
    }
    val count = losses.size
    val sorted = losses.sortedWith(
        compareByDescending<RankedTimeLoss> { it.lossSeconds }
            .thenBy { it.window.startProgressMeters }
            .thenBy { it.lapStartTime },
    )
    return TimeLossRanking(
        stamp = stampOf(approved, TimeLossAlgorithm),
        referenceLap = reference.times.lapReference,
        losses = if (maximumResults >= 0 && sorted.size > maximumResults) sorted.subList(0, maximumResults) else sorted,
        observationCount = count,
        comparedLapCount = compared,
        untimedWindowCount = untimed,
        valid = true,
    )
}

/**
 * Losses of each run's fastest lap of [population] (timed as [sectors]), or with [allLaps] of
 * every lap, against [actualBest], as Telemetry's rankOutingTimeLosses does. Unavailable without
 * a timed actual best.
 */
fun rankPopulationTimeLosses(
    approved: ApprovedSegments,
    sectors: PopulationSectors,
    population: List<PopulationLap>,
    actualBest: LapSectorTimes?,
    allLaps: Boolean = false,
    maximumResults: Int = 50,
): TimeLossRanking {
    if (actualBest == null) return TimeLossRanking(unavailableReason = TimeLossNoReference)
    val timed = sectors.times.mapIndexed { i, times -> TimedLapSectors(times, population[i].start) }
    var start = 0.0
    for (lap in timed) if (lap.times.lapReference == actualBest.lapReference) start = lap.startTime
    val compared = if (allLaps) {
        timed
    } else {
        // Each run's fastest lap, so warm-up laps do not dominate.
        val fastest = LinkedHashMap<String, Int>()
        timed.forEachIndexed { i, lap ->
            val current = fastest[population[i].runId]
            if (current == null || lap.times.lapSeconds < timed[current].times.lapSeconds) {
                fastest[population[i].runId] = i
            }
        }
        fastest.values.map { timed[it] }
    }
    return rankTimeLosses(
        approved, sectors.axis.lengthMeters, compared, TimedLapSectors(actualBest, start), maximumResults,
    )
}
