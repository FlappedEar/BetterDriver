package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType

// A composite lap as a film (FBBD-163, FBBD-178): each corner and straight taken from the lap the
// composite chose for it, in track order from the line, so the parts played back to back are one
// lap at the composite's pace. The composite is the realistic optimal (only compatible neighbours
// joined) or the raw theoretical best (each segment's fastest time, whatever its neighbours).

/** Which composite lap an [OptimalLap] is (FBBD-178); the two are never called by one name. */
enum class OptimalKind {
    /** The fastest lap the driver has shown they can combine: compatible joints only. */
    RealisticOptimal,

    /** The sum of each segment's fastest time, which no lap may be able to drive. */
    RawTheoreticalBest,
}

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
 * A composite lap ([kind]) of a session as [parts] in track order, [totalSeconds] long, from the
 * eligible laps; [fastestLapNumber] is the lap it is measured against.
 */
data class OptimalLap(
    val parts: List<OptimalPart>,
    val totalSeconds: Double,
    val fastestLapNumber: Int?,
    val kind: OptimalKind = OptimalKind.RawTheoreticalBest,
) {
    /** The laps the parts come from, in lap order. */
    val laps: List<Int> get() = parts.map { it.lapNumber }.distinct().sorted()
}

/** Why a session has no [OptimalLap]. */
enum class OptimalLapUnavailable {
    /** The corners could not be found on the best lap, so there is no theoretical best. */
    NoSegments,

    /** A corner or straight has no fully timed pass on any counted lap. */
    IncompleteCoverage,

    /** No chain of counted laps joins compatibly through every corner and straight (realistic optimal only). */
    NoCompatibleChain,
}

/**
 * The composite lap [kind] of [analysis] as parts of its laps, or why there is none. Each
 * segment's part runs from its start to its end crossing on the lap the composite chose for it
 * (the times sector timing measured it with), so the parts add up to the composite's sectors.
 */
@Suppress("ReturnCount")
fun optimalLap(analysis: SessionAnalysis, kind: OptimalKind = OptimalKind.RealisticOptimal): OptimalLapResult {
    val best = analysis.theoreticalBest
    if (analysis.segments == null || best == null || !best.valid) {
        return OptimalLapResult(why = OptimalLapUnavailable.NoSegments)
    }
    if (best.totalSeconds == null) return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
    val (choices, total) = choicesOf(analysis, kind)
        ?: return OptimalLapResult(why = OptimalLapUnavailable.NoCompatibleChain)
    val laps = analysis.laps.timedLaps
    val fastestIndex = analysis.laps.fastestLapIndex
    val fastest = fastestIndex?.let { laps.getOrNull(it) }
    val stretches = ArrayList<Stretch>()
    for ((segmentId, lapNumber) in choices) {
        val index = laps.indexOfFirst { it.number == lapNumber }
        val timing = analysis.lapTimes.getOrNull(index)?.sector(segmentId)
        if (lapNumber == null || index < 0 || timing == null) {
            return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
        }
        val reference = fastestIndex?.let { analysis.lapTimes.getOrNull(it)?.sector(segmentId) }
        stretches += stretchesOf(timing, laps[index], reference, fastest)
            ?: return OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
    }
    var offset = 0.0
    val parts = stretches.sortedBy { it.fromMeters }.map { s ->
        s.part.copy(offsetSeconds = offset).also { offset += it.seconds }
    }
    return OptimalLapResult(OptimalLap(parts, total, fastest?.number, kind))
}

/**
 * The lap each segment of composite [kind] comes from (by segment id; null when none won it) and
 * its total; null when [analysis] has no realistic optimal.
 */
private fun choicesOf(analysis: SessionAnalysis, kind: OptimalKind): Pair<List<Pair<String, Int?>>, Double>? =
    when (kind) {
        OptimalKind.RawTheoreticalBest -> analysis.theoreticalBest?.let { best ->
            best.sectors.map { it.segmentId to it.sourceLapReference as? Int } to (best.totalSeconds ?: 0.0)
        }
        OptimalKind.RealisticOptimal -> analysis.realisticOptimal?.let { r ->
            r.sectors.map { it.segmentId to it.lapNumber } to r.totalSeconds
        }
    }

/**
 * The three lap times of [analysis] (FBBD-178): best actual lap, realistic optimal and raw
 * theoretical best; null when it has no eligible lap.
 */
fun lapBenchmarks(analysis: SessionAnalysis): LapBenchmarks? {
    val fastest = analysis.laps.fastestLapIndex?.let { analysis.laps.timedLaps.getOrNull(it) } ?: return null
    val raw = analysis.theoreticalBest?.takeIf { it.valid }?.totalSeconds
    return LapBenchmarks(
        bestActualSeconds = fastest.durationSeconds,
        bestActualLapNumber = fastest.number,
        realisticOptimalSeconds = analysis.realisticOptimal?.totalSeconds?.takeIf { raw != null },
        rawTheoreticalSeconds = raw,
    )
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
