package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.segments.SectorTime
import kotlin.math.abs

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
     * the next lap, so the pass is one stretch of time (and video); when no lap follows without a
     * break, the pass ends where its lap ends, untimed.
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
        val (exit, seconds) = when {
            sector?.startTime == null -> null to null
            sector.endProgressMeters < sector.startProgressMeters -> overTheLine(analysis, i, segmentId, sector)
            sector.endTime != null && sector.endTime > sector.startTime -> sector.endTime to sector.seconds
            else -> null to null
        }
        CornerPass(
            lapIndex = i,
            lapNumber = lap.number,
            entryTime = sector?.startTime,
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

/** The next lap starts this close to a lap's end when the drive went on through the line. */
private const val ContiguousLapSeconds = 0.5

/**
 * The exit and time of a pass through a corner over the start/finish line, entered on lap [i]
 * ([sector] its timing there): the lap's own sector joins its tail with its earlier head, so the
 * exit is read on the next lap, when it follows on without a break. Without one the pass ends
 * where lap [i] ends, untimed.
 */
private fun overTheLine(
    analysis: SessionAnalysis,
    i: Int,
    segmentId: String,
    sector: SectorTime,
): Pair<Double?, Double?> {
    val entry = sector.startTime ?: return null to null
    val lap = analysis.laps.timedLaps[i]
    val next = analysis.laps.timedLaps.getOrNull(i + 1)
    val follows = next != null && abs(next.startTelemetryTime - lap.endTelemetryTime) <= ContiguousLapSeconds
    val exit = if (follows) analysis.lapTimes.getOrNull(i + 1)?.sector(segmentId)?.endTime else null
    return when {
        // Timed only when this lap covers the corner's part before the line (seconds is set).
        exit != null && exit > entry -> exit to sector.seconds?.let { exit - entry }
        lap.endTelemetryTime > entry -> lap.endTelemetryTime to null
        else -> null to null
    }
}
