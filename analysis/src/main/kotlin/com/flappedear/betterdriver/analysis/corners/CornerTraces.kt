package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.abs

// Every pass through one corner as traces (FBBD-93, cycle priority 9): speed, brake and throttle
// by distance over the corner and its approach, one trace per counted lap, all on one distance
// scale so they lie over each other.

/** The traces start this far before the corner entry, so the braking shows. */
const val CornerTraceLeadMeters = 120.0

/** And end this far after the corner exit, so the drive out shows. */
const val CornerTraceTailMeters = 60.0

/** Most points kept per channel of one pass. */
const val MaxCornerTracePoints = 400

/** One counted lap's pass on the traces' distance scale: runs of points (a GPS gap splits runs). */
data class PassTrace(
    val lapIndex: Int,
    val speed: List<List<ChartPoint>>,
    val brake: List<List<ChartPoint>> = emptyList(),
    val throttle: List<List<ChartPoint>> = emptyList(),
)

/**
 * The traces of a corner's passes. Distances are metres from the start of the window, which runs
 * from [CornerTraceLeadMeters] before the corner entry ([entryMeters]) to [CornerTraceTailMeters]
 * after its exit ([exitMeters]), [lengthMeters] in all. A corner over the start/finish line is one
 * stretch: its part after the line is read on the next lap, and an approach that begins before the
 * line is read on the previous lap. [speedUnit] and the provenances say
 * what the values are; a channel the session does not have has no runs.
 */
data class CornerTraces(
    val lengthMeters: Double,
    val entryMeters: Double,
    val exitMeters: Double,
    val passes: List<PassTrace>,
    val speedUnit: String = "",
    val speedProvenance: Provenance? = null,
    val brakeProvenance: Provenance? = null,
    val throttleProvenance: Provenance? = null,
) {
    fun of(lapIndex: Int): PassTrace? = passes.firstOrNull { it.lapIndex == lapIndex }

    val hasBrake: Boolean get() = passes.any { it.brake.isNotEmpty() }
    val hasThrottle: Boolean get() = passes.any { it.throttle.isNotEmpty() }
}

/**
 * The traces of timed laps [lapIndices] through corner [segmentId] of [analysis], read from
 * [session]. Null without an axis, or when the corner is not one of the analysis's segments (its
 * corners were changed since: another revision).
 */
@Suppress("ReturnCount")
fun cornerTraces(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    segmentId: String,
    lapIndices: List<Int>,
): CornerTraces? {
    val axis = analysis.axis ?: return null
    val length = axis.lengthMeters
    if (length <= 0.0) return null
    val corner = analysis.segments?.segments?.firstOrNull { it.id == segmentId } ?: return null
    // On one unwrapped scale: the next lap's progress runs on from [length].
    val wraps = corner.endProgressMeters < corner.startProgressMeters
    val end = if (wraps) corner.endProgressMeters + length else corner.endProgressMeters
    // On a loop the approach may begin before the line, on the previous lap (progress below 0 here);
    // a point-to-point run starts at its start.
    val lead = corner.startProgressMeters - CornerTraceLeadMeters
    val from = if (axis.closed) lead else lead.coerceAtLeast(0.0)
    // On a loop the window may run on over the line into the next lap; a point-to-point run ends at its finish.
    val limit = if (axis.closed) end + CornerTraceTailMeters else minOf(end + CornerTraceTailMeters, length)
    val laps = analysis.laps.timedLaps
    val passes = lapIndices.filter { it in laps.indices && it in analysis.traces.indices }.map { i ->
        val runs = passRuns(analysis, i, from, limit)
        PassTrace(
            i,
            channelRuns(session, Aliases.SPEED, runs, from),
            channelRuns(session, Aliases.BRAKE, runs, from),
            channelRuns(session, Aliases.THROTTLE, runs, from),
        )
    }
    return CornerTraces(
        lengthMeters = limit - from,
        entryMeters = corner.startProgressMeters - from,
        exitMeters = end - from,
        passes = passes,
        speedUnit = session.channel(Aliases.SPEED)?.unit.orEmpty(),
        speedProvenance = analysis.provenance.speed,
        brakeProvenance = analysis.provenance.brake,
        throttleProvenance = analysis.provenance.throttle,
    )
}

/**
 * Lap [i]'s samples within [from, limit] on its unwrapped scale. On a loop a window past the line
 * runs on into the next lap, and one that begins before the line (a negative [from]) is read on the
 * previous lap up to the line, when that lap runs on into this one without a break.
 */
private fun passRuns(analysis: SessionAnalysis, i: Int, from: Double, limit: Double): List<List<At>> {
    val axis = analysis.axis ?: return emptyList()
    val length = axis.lengthMeters
    val traces = analysis.traces
    val next = (i + 1).takeIf { axis.closed && follows(analysis, i) && it in traces.indices }
    val previous = (i - 1).takeIf { axis.closed && from < 0.0 && it in traces.indices && follows(analysis, it) }
    val before = previous?.let { window(traces[it], -length, from, 0.0) }.orEmpty()
    val own = window(traces[i], 0.0, if (previous != null) 0.0 else from, limit)
    val after = next?.let { window(traces[it], length, from, limit) }.orEmpty()
    return before + own + after
}

/** A sample of a pass: when, and where on the unwrapped scale. */
private class At(val time: Double, val meters: Double)

/** Lap [i]'s next timed lap follows on without a break, as a pass over the line is read. */
private fun follows(analysis: SessionAnalysis, i: Int): Boolean {
    val laps = analysis.laps.timedLaps
    val next = laps.getOrNull(i + 1) ?: return false
    return abs(next.startTelemetryTime - laps[i].endTelemetryTime) <= ContiguousLapSeconds
}

/** The valid samples of [trace] (offset by [offset] metres) within [from, to], split where a sample is not valid. */
@Suppress("NestedBlockDepth")
private fun window(trace: List<ProgressSegment>, offset: Double, from: Double, to: Double): List<List<At>> {
    val runs = mutableListOf<List<At>>()
    for (segment in trace) {
        var run = mutableListOf<At>()
        for (s in segment.samples) {
            val m = s.progressMeters + offset
            if (!s.valid || m < from || m > to) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            } else {
                run += At(s.telemetryTime, m)
            }
        }
        if (run.isNotEmpty()) runs += run
    }
    return runs
}

/**
 * Channel [name] of [session] along [runs], thinned evenly to at most [MaxCornerTracePoints], at
 * metres from [from]; a sample without a value ends a run. Empty when the session has no such channel.
 */
@Suppress("NestedBlockDepth")
private fun channelRuns(
    session: TelemetrySession,
    name: String,
    runs: List<List<At>>,
    from: Double,
): List<List<ChartPoint>> {
    if (session.channel(name) == null) return emptyList()
    val total = runs.sumOf { it.size }
    val stride = maxOf(1, (total + MaxCornerTracePoints - 1) / MaxCornerTracePoints)
    val out = mutableListOf<List<ChartPoint>>()
    var ordinal = 0
    for (run in runs) {
        var points = mutableListOf<ChartPoint>()
        for (a in run) {
            if (ordinal++ % stride != 0 && a !== run.last()) continue
            val v = session.valueAt(name, a.time)
            if (v == null) {
                if (points.isNotEmpty()) out += points
                points = mutableListOf()
            } else {
                points += ChartPoint(a.meters - from, v)
            }
        }
        if (points.isNotEmpty()) out += points
    }
    return out
}
