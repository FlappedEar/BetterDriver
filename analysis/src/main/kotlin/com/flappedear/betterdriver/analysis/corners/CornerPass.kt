package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.SessionAnalysis

/**
 * One counted lap's pass through one corner (FBBD-153): when it entered and left the corner (from
 * the corner's sector times on the lap), its time through it, and the braking point and minimum
 * speed of the Corner Analyzer's metrics. Times are telemetry seconds, as on the lap.
 */
data class CornerPass(
    /** Index in [com.flappedear.betterdriver.analysis.laps.LapSession.timedLaps]. */
    val lapIndex: Int,
    val lapNumber: Int,
    /** When the lap crossed the corner's entry; null where its GPS does not reach it. */
    val entryTime: Double?,
    /**
     * When the drive crossed the corner's exit. For a corner over the start/finish line this is on
     * the next lap: the drive goes on through the line, so the pass is one stretch of time (and video).
     */
    val exitTime: Double?,
    /** Time through the corner; null unless the lap's GPS covers all of it. */
    val seconds: Double?,
    /** Where the lap started to brake for the corner, telemetry seconds; null when not found. */
    val brakingTime: Double?,
    /** Metres before the corner entry the lap started to brake. */
    val brakingBeforeEntryMeters: Double?,
    /** The braking point is inferred from deceleration, not read from a brake channel. */
    val brakingInferred: Boolean,
    val minimumSpeed: Double?,
    /** Unit of [minimumSpeed]. */
    val speedUnit: String,
)

/**
 * Every counted lap's pass through corner [segmentId] of [analysis], fastest through the corner
 * first; a pass without a time comes last, in lap order. Empty when the corner was not measured.
 */
fun cornerPasses(analysis: SessionAnalysis, segmentId: String): List<CornerPass> {
    val metrics = analysis.cornerMetrics[segmentId] ?: return emptyList()
    val laps = analysis.laps.timedLaps
    return laps.indices.filter { laps[it].referenceEligible }.mapNotNull { i ->
        val lap = laps[i]
        val m = metrics.getOrNull(i) ?: return@mapNotNull null
        val sector = analysis.lapTimes.getOrNull(i)?.sector(segmentId)
        val entry = sector?.startTime
        val seconds = sector?.seconds
        // Over the line the exit is timed at the start of the same lap, so it is reached `seconds`
        // after the entry instead.
        val exit = when {
            entry != null && seconds != null -> entry + seconds
            entry != null && sector.endTime != null && sector.endTime > entry -> sector.endTime
            else -> null
        }
        CornerPass(
            lapIndex = i,
            lapNumber = lap.number,
            entryTime = entry,
            exitTime = exit,
            seconds = seconds,
            brakingTime = m.braking.brakingPointTime,
            brakingBeforeEntryMeters = m.braking.distanceBeforeEntryMeters,
            brakingInferred = m.braking.provenance == "inferred",
            minimumSpeed = m.speeds.minimum.value,
            speedUnit = m.speeds.unit,
        )
    }.sortedWith(compareBy<CornerPass, Double?>(nullsLast()) { it.seconds }.thenBy { it.lapNumber })
}
