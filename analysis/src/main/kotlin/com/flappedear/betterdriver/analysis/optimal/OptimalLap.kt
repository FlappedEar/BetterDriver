package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType

// The theoretical best lap as a film (FBBD-163): each corner and straight of the theoretical best,
// taken from the lap that drove it fastest, in track order from the line, so the parts played back
// to back are one lap at the theoretical best's pace.

/**
 * One stretch of the theoretical best lap: segment [segmentId] ([name]) as lap [lapNumber] drove
 * it, telemetry time [startTime] to [endTime]. [offsetSeconds] is the theoretical lap's time where
 * it starts. [referenceSeconds] is the fastest lap's time through the same stretch, null when it
 * has none. A segment over the start/finish line is two parts of the same lap, one after the line
 * at the start of the theoretical lap and one before it at the end ([overTheLine]).
 */
data class OptimalPart(
    val segmentId: String,
    val name: String,
    val corner: Boolean,
    val lapNumber: Int,
    val startTime: Double,
    val endTime: Double,
    val offsetSeconds: Double,
    val referenceSeconds: Double?,
    val overTheLine: Boolean = false,
) {
    val seconds: Double get() = endTime - startTime
}

/**
 * The theoretical best of a session as [parts] in track order, [totalSeconds] long (the
 * theoretical best's total), from the eligible laps; [fastestLapNumber] is the lap it is measured
 * against.
 */
data class OptimalLap(val parts: List<OptimalPart>, val totalSeconds: Double, val fastestLapNumber: Int?) {
    /** The laps the parts come from, in lap order. */
    val laps: List<Int> get() = parts.map { it.lapNumber }.distinct().sorted()
}

/** Why a session has no [OptimalLap]. */
enum class OptimalLapUnavailable {
    /** The corners could not be found on the best lap, so there is no theoretical best. */
    NoSegments,

    /** A corner or straight has no fully timed pass on any counted lap. */
    IncompleteCoverage,
}

/**
 * The theoretical best of [analysis] as parts of its laps, or why there is none. Each segment's
 * part runs from its start to its end crossing on the lap that won it (the times sector timing
 * measured it with), so the parts add up to the theoretical best's total.
 */
@Suppress("ReturnCount")
fun optimalLap(analysis: SessionAnalysis): OptimalLapResult {
    val best = analysis.theoreticalBest
    if (analysis.segments == null || best == null || !best.valid) {
        return OptimalLapResult(why = OptimalLapUnavailable.NoSegments)
    }
    val total = best.totalSeconds ?: return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
    val laps = analysis.laps.timedLaps
    val fastestIndex = analysis.laps.fastestLapIndex
    val fastest = fastestIndex?.let { laps.getOrNull(it) }
    val stretches = ArrayList<Stretch>()
    for (sector in best.sectors) {
        val lapNumber = sector.sourceLapReference as? Int
        val index = laps.indexOfFirst { it.number == lapNumber }
        val timing = analysis.lapTimes.getOrNull(index)?.sector(sector.segmentId)
        if (lapNumber == null || index < 0 || timing == null) {
            return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
        }
        val reference = fastestIndex?.let { analysis.lapTimes.getOrNull(it)?.sector(sector.segmentId) }
        stretches += stretchesOf(timing, laps[index], reference, fastest)
            ?: return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
    }
    var offset = 0.0
    val parts = stretches.sortedBy { it.fromMeters }.map { s ->
        s.part.copy(offsetSeconds = offset).also { offset += it.seconds }
    }
    return OptimalLapResult(OptimalLap(parts, total, fastest?.number))
}

/** [lap], or [why] there is none. */
data class OptimalLapResult(val lap: OptimalLap? = null, val why: OptimalLapUnavailable? = null)

/** A part before its offset is known, with where it starts on the lap. */
private class Stretch(val fromMeters: Double, val part: OptimalPart)

/**
 * The stretches of [timing], timed on [lap]: one, or two for a segment over the line (after the
 * line, from the lap's start; and before it, to the lap's end). The fastest lap's time through each
 * comes from [reference] on [fastest]. Null when a crossing is missing.
 */
private fun stretchesOf(timing: SectorTime, lap: TimedLap, reference: SectorTime?, fastest: TimedLap?): List<Stretch>? {
    val start = timing.startTime
    val end = timing.endTime
    val overTheLine = timing.endProgressMeters < timing.startProgressMeters
    fun part(from: Double, to: Double, ref: Double?) = OptimalPart(
        timing.segmentId, timing.name, timing.type == TrackSegmentType.Corner, lap.number, from, to,
        offsetSeconds = 0.0, referenceSeconds = ref, overTheLine = overTheLine,
    )
    return when {
        start == null || end == null -> null
        !overTheLine -> if (end > start) {
            val ref = minus(reference?.endTime, reference?.startTime)
            listOf(Stretch(timing.startProgressMeters, part(start, end, ref)))
        } else {
            null
        }
        end <= lap.startTelemetryTime || lap.endTelemetryTime <= start -> null
        else -> listOf(
            Stretch(0.0, part(lap.startTelemetryTime, end, minus(reference?.endTime, fastest?.startTelemetryTime))),
            Stretch(
                timing.startProgressMeters,
                part(start, lap.endTelemetryTime, minus(fastest?.endTelemetryTime, reference?.startTime)),
            ),
        )
    }
}

/** [a] minus [b], when both are known. */
private fun minus(a: Double?, b: Double?): Double? = if (a != null && b != null) a - b else null

/** Session-clock microseconds of [part]'s start and end; telemetry time 0 is at [originMicros]. */
fun OptimalPart.sessionRange(originMicros: Long): LongRange =
    sessionMicros(originMicros, startTime)..sessionMicros(originMicros, endTime)
