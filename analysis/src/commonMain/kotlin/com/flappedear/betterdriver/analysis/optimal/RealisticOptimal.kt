package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.analysis.corners.lateralOffsetMeters
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import kotlin.math.abs

// The realistic optimal lap (FBBD-178). The raw theoretical best adds up each corner's and
// straight's fastest time on whichever lap drove it, so it can join a corner left at 140 km/h on
// lap 3 to a straight entered at 120 km/h on lap 7: no lap could drive that. The realistic optimal
// is the fastest lap the driver has shown they can put together: neighbouring segments come from
// different laps only where those laps were doing the same thing at the joint, in speed, place on
// the track, direction and pedals. Same-lap neighbours always join, so the fastest lap itself is
// always a candidate and the realistic optimal is never slower than the best actual lap, never
// faster than the raw theoretical best.
//
// The search is a dynamic programme over the segments in track order (by their canonical
// SegmentId, FBBD-172): for each segment and each lap that timed it, the fastest compatible chain
// of earlier segments ending on that lap. A segment over the start/finish line is driven by one
// lap at both ends of the realistic lap, as sector timing times it, so the programme is run once
// for each lap that segment can come from.

/** How close two laps must be at a joint for the realistic optimal to join them there. */
data class JointThresholds(
    /** Speed difference, km/h. */
    val speedKmh: Double = 3.0,
    /**
     * Difference in distance from the reference line, metres. A car's width is ~1.8 m and a
     * phone or 10 Hz GNSS repeats a line to within ~0.5 m lap to lap at the same place, so 1.5 m
     * is the same line with room for the receiver, not a different one.
     */
    val lateralMeters: Double = 1.5,
    /**
     * Difference in direction across the track, radians: the change of distance from the
     * reference line per metre along it, measured over [TrajectoryWindowMeters] either side of
     * the joint. 0.05 rad (~3°) is 1 m of drift across the track in 20 m.
     */
    val headingRadians: Double = 0.05,
    /** Difference in longitudinal acceleration, g: braking as hard, accelerating as hard. */
    val accelerationG: Double = 0.25,
)

/** What the car is doing along the track at a joint, from its longitudinal acceleration. */
enum class PedalState { Braking, Coasting, Accelerating }

/** Below this, g, the car is braking. Gentle lift-off decelerates ~0.1 g. */
const val BrakingBelowG = -0.15

/** Above this, g, the car is accelerating. */
const val AcceleratingAboveG = 0.05

/** Metres either side of a joint the direction across the track is measured over. */
const val TrajectoryWindowMeters = 10.0

/** Seconds either side of a joint the longitudinal acceleration is measured over. */
const val AccelerationWindowSeconds = 0.5

/** One lap at one joint: when it crossed it, how fast, where on the track, which way, doing what. */
data class JointState(
    val time: Double,
    val speedKmh: Double?,
    /** Signed distance from the reference line, metres (positive left). */
    val lateralMeters: Double?,
    /** Change of [lateralMeters] per metre along the track around the joint. */
    val headingRadians: Double?,
    val accelerationG: Double?,
) {
    val pedals: PedalState?
        get() = accelerationG?.let {
            when {
                it < BrakingBelowG -> PedalState.Braking
                it > AcceleratingAboveG -> PedalState.Accelerating
                else -> PedalState.Coasting
            }
        }
}

/**
 * The joint between [fromSegmentId] as lap [fromLap] drove it and [toSegmentId] as lap [toLap]
 * did, at [progressMeters] on the axis, with how far apart the laps were there. The differences
 * are null on a same-lap joint, which is a real lap's own continuity.
 */
data class RealisticJoint(
    val fromSegmentId: String,
    val toSegmentId: String,
    val progressMeters: Double,
    val fromLap: Int,
    val toLap: Int,
    val from: JointState?,
    val to: JointState?,
) {
    val sameLap: Boolean get() = fromLap == toLap
    val speedDifferenceKmh: Double? get() = diff(from?.speedKmh, to?.speedKmh)
    val lateralDifferenceMeters: Double? get() = diff(from?.lateralMeters, to?.lateralMeters)
    val headingDifferenceRadians: Double? get() = diff(from?.headingRadians, to?.headingRadians)
    val accelerationDifferenceG: Double? get() = diff(from?.accelerationG, to?.accelerationG)
}

/** One segment of the realistic optimal: lap [lapNumber]'s time through it. */
data class RealisticSector(val segmentId: String, val name: String, val lapNumber: Int, val seconds: Double)

/**
 * The realistic optimal lap: [sectors] in approved order, each from the lap the search chose,
 * [joints] between neighbours in track order, and the total [totalSeconds]. [thresholds] are the
 * limits every cross-lap joint is within.
 */
data class RealisticOptimalLap(
    val sectors: List<RealisticSector>,
    val joints: List<RealisticJoint>,
    val totalSeconds: Double,
    val thresholds: JointThresholds,
) {
    /** The laps it is made of, in lap order. */
    val laps: List<Int> get() = sectors.map { it.lapNumber }.distinct().sorted()

    /** Joints where it changes lap. */
    val crossLapJoints: List<RealisticJoint> get() = joints.filter { !it.sameLap }
}

/**
 * The three lap times a session is judged by (FBBD-178), kept apart everywhere: the fastest lap
 * actually driven, the fastest the driver has shown they can combine, and the sum of each
 * segment's fastest time, which no lap may ever drive.
 */
data class LapBenchmarks(
    /** Best actual lap: the fastest eligible lap, as timed. */
    val bestActualSeconds: Double,
    val bestActualLapNumber: Int,
    /** Realistic optimal; null when there are no segments or no complete compatible chain. */
    val realisticOptimalSeconds: Double?,
    /** Raw theoretical best; null when a segment has no time on any eligible lap. */
    val rawTheoreticalSeconds: Double?,
)

/** Whether [a] and [b] (not the same lap) can be joined under [t]; an unknown never joins. */
fun compatible(a: JointState, b: JointState, t: JointThresholds): Boolean {
    val within = listOf(
        diff(a.speedKmh, b.speedKmh) to t.speedKmh,
        diff(a.lateralMeters, b.lateralMeters) to t.lateralMeters,
        diff(a.headingRadians, b.headingRadians) to t.headingRadians,
        diff(a.accelerationG, b.accelerationG) to t.accelerationG,
    ).all { (difference, limit) -> difference != null && difference <= limit }
    val flip = setOf(a.pedals, b.pedals) == setOf(PedalState.Braking, PedalState.Accelerating)
    return within && !flip
}

private fun diff(a: Double?, b: Double?): Double? = if (a != null && b != null) abs(a - b) else null

/** A lap that can be used: the lap, its sector times and its trace on the axis. */
private class Candidate(val lap: TimedLap, val times: LapSectorTimes, val trace: List<ProgressSegment>)

/** One segment as one candidate lap drove it. */
private class Pick(val candidate: Candidate, val sector: SectorTime) {
    val seconds: Double = sector.seconds ?: Double.POSITIVE_INFINITY
}

/** A chain of picks, in the order searched, and its total time. */
private class Chain(val picks: List<Pick>, val seconds: Double)

/**
 * The realistic optimal of the eligible laps of [laps] (the timed laps, with [lapTimes] and
 * [traces] in the same order) through [approved] on [axis]; null without segments or when no
 * compatible chain covers every segment. [fastestIndex] is the best actual lap, the answer when no
 * combination beats it.
 */
@Suppress("LongParameterList", "ReturnCount")
fun realisticOptimal(
    session: TelemetrySession,
    axis: ProgressAxis,
    approved: ApprovedSegments,
    laps: List<TimedLap>,
    lapTimes: List<LapSectorTimes>,
    traces: List<List<ProgressSegment>>,
    fastestIndex: Int?,
    thresholds: JointThresholds = JointThresholds(),
): RealisticOptimalLap? {
    val candidates = laps.indices
        .filter { laps[it].referenceEligible && lapTimes.getOrNull(it)?.valid == true && traces.getOrNull(it) != null }
        .map { Candidate(laps[it], lapTimes[it], traces[it]) }
    if (approved.segments.isEmpty() || !axis.valid || candidates.isEmpty()) return null
    val search = Search(JointProbe(session, axis), candidates, thresholds)
    val loop = Loop(approved, axis.closed)
    val found = search.best(loop.rest, loop.closing) ?: return null
    val bySegment = found.picks.associateBy { it.sector.segmentId }

    // Never slower than the best actual lap: when its own time beats every combination, it is the answer.
    val alone = fastestIndex?.let { laps.getOrNull(it) }
        ?.let { f -> candidates.firstOrNull { it.lap.number == f.number } }
        ?.takeIf { c -> approved.segments.all { c.times.sector(it.id)?.seconds != null } }
        ?.takeIf { it.lap.durationSeconds <= found.seconds }
    fun pickOf(s: TrackSegment): Pick = alone?.let { Pick(it, it.times.sector(s.id)!!) } ?: bySegment.getValue(s.id)

    val sectors = approved.segments.map { s ->
        val p = pickOf(s)
        RealisticSector(s.id, s.name, p.candidate.lap.number, p.seconds)
    }
    val joints = loop.inTrackOrder.zipWithNext().map { (a, b) -> search.joint(pickOf(a), pickOf(b)) }
    return RealisticOptimalLap(sectors, joints, alone?.lap?.durationSeconds ?: found.seconds, thresholds)
}

/**
 * The segments of [approved] in the order the search takes them. On a circuit ([closed]) the lap
 * runs on over the line, so the last segment must also lead into the first: a segment over the line
 * closes the loop itself; otherwise the first segment closes it. Either way the [closing] segment's
 * lap is fixed for each run of the search over the [rest]. A point-to-point run has no such joint.
 */
private class Loop(approved: ApprovedSegments, closed: Boolean) {
    private val order = approved.segments.sortedBy { it.startProgressMeters }
    private val wrap = order.firstOrNull { it.endProgressMeters < it.startProgressMeters }
    private val chain = order.filter { it !== wrap }
    val closing: TrackSegment? = wrap ?: chain.firstOrNull()?.takeIf { closed && chain.size > 1 }
    val rest: List<TrackSegment> = chain.filter { it !== closing }

    /** Every segment in track order, with the one that closes the loop again where it does. */
    val inTrackOrder: List<TrackSegment> = when {
        wrap != null -> listOf(wrap) + chain + wrap
        closing != null -> chain + closing
        else -> chain
    }
}

/** The search over [candidates], measuring each lap at the joints once with [probe]. */
private class Search(
    private val probe: JointProbe,
    private val candidates: List<Candidate>,
    private val thresholds: JointThresholds,
) {
    private val states = HashMap<Triple<String, Boolean, Int>, JointState?>()

    /** Lap [p]'s state where it leaves its segment ([atEnd]) or enters it. */
    private fun state(p: Pick, atEnd: Boolean): JointState? =
        states.getOrPut(Triple(p.sector.segmentId, atEnd, p.candidate.lap.number)) {
            val progress = if (atEnd) p.sector.endProgressMeters else p.sector.startProgressMeters
            val time = if (atEnd) p.sector.endTime else p.sector.startTime
            time?.let { probe.at(p.candidate.trace, progress, it) }
        }

    /** Whether [b] can follow [a]: the same lap, or both measured and compatible at the joint. */
    fun joins(a: Pick, b: Pick): Boolean {
        if (a.candidate === b.candidate) return true
        val x = state(a, atEnd = true)
        val y = state(b, atEnd = false)
        return x != null && y != null && compatible(x, y, thresholds)
    }

    fun joint(a: Pick, b: Pick): RealisticJoint {
        val same = a.candidate === b.candidate
        return RealisticJoint(
            a.sector.segmentId, b.sector.segmentId, a.sector.endProgressMeters,
            a.candidate.lap.number, b.candidate.lap.number,
            if (same) null else state(a, atEnd = true),
            if (same) null else state(b, atEnd = false),
        )
    }

    /** Each lap that timed segment [s]. */
    private fun options(s: TrackSegment): List<Pick> =
        candidates.mapNotNull { c -> c.times.sector(s.id)?.takeIf { it.seconds != null }?.let { Pick(c, it) } }

    /**
     * The fastest compatible chain through [chain] (in track order) and [wrap], the segment that
     * closes the loop (over the line, or the first after it), which must follow the last of
     * [chain] and lead into its first: the search is run once for each lap that can drive it.
     * Null when nothing gets through.
     */
    @Suppress("ReturnCount")
    fun best(chain: List<TrackSegment>, wrap: TrackSegment?): Chain? {
        val stages = chain.map(::options)
        if (stages.any { it.isEmpty() }) return null
        val ends = wrap?.let(::options) ?: return through(stages, null)
        return ends.mapNotNull { w -> through(stages, w)?.let { Chain(it.picks + w, it.seconds + w.seconds) } }
            .minByOrNull { it.seconds }
    }

    /**
     * The dynamic programme: for each stage and each of its picks, the fastest chain ending on it.
     * With [wrap], the first stage must follow its end and the last must lead into its start.
     */
    @Suppress("ReturnCount")
    private fun through(stages: List<List<Pick>>, wrap: Pick?): Chain? {
        if (stages.isEmpty()) return Chain(emptyList(), 0.0)
        var cost = DoubleArray(stages[0].size) { i ->
            val p = stages[0][i]
            if (wrap == null || joins(wrap, p)) p.seconds else Double.POSITIVE_INFINITY
        }
        val from = ArrayList<IntArray>()
        for (k in 1 until stages.size) {
            val (next, back) = step(stages[k - 1], cost, stages[k])
            cost = next
            from += back
        }
        val last = stages.last()
        val end = last.indices
            .filter { cost[it].isFinite() && (wrap == null || joins(last[it], wrap)) }
            .minByOrNull { cost[it] } ?: return null
        val picks = ArrayList<Pick>()
        var i = end
        for (k in stages.indices.reversed()) {
            picks += stages[k][i]
            if (k > 0) i = from[k - 1][i]
        }
        return Chain(picks.reversed(), cost[end])
    }

    /** One stage of [through]: the fastest way onto each of [stage]'s picks from [previous]. */
    private fun step(previous: List<Pick>, before: DoubleArray, stage: List<Pick>): Pair<DoubleArray, IntArray> {
        val cost = DoubleArray(stage.size) { Double.POSITIVE_INFINITY }
        val from = IntArray(stage.size) { -1 }
        for ((j, o) in stage.withIndex()) {
            for ((i, p) in previous.withIndex()) {
                val total = before[i] + o.seconds
                if (total < cost[j] && joins(p, o)) {
                    cost[j] = total
                    from[j] = i
                }
            }
        }
        return cost to from
    }
}

/** Measures a lap's [JointState] at a joint from the session's channels. */
private class JointProbe(private val session: TelemetrySession, private val axis: ProgressAxis) {
    private val speedName = session.aliases[Aliases.SPEED]
    private val metresPerSecond = speedName?.let { metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, it)) }

    /** Lap [trace] crossing [progress] at [time]. */
    fun at(trace: List<ProgressSegment>, progress: Double, time: Double): JointState {
        val before = speedAt(trace, time - AccelerationWindowSeconds)
        val after = speedAt(trace, time + AccelerationWindowSeconds)
        val acceleration = if (before != null && after != null) {
            (after - before) / KmhPerMetrePerSecond / (2 * AccelerationWindowSeconds) / StandardGravity
        } else {
            null
        }
        val lateral = lateralAt(time, progress)
        val heading = headingAt(trace, progress, lateral)
        return JointState(time, speedAt(trace, time), lateral, heading, acceleration)
    }

    /**
     * The drift across the track around [progress], where the lap is [lateral] off the line: over
     * the window either side, or on one side only at the line, where the lap's trace ends.
     */
    private fun headingAt(trace: List<ProgressSegment>, progress: Double, lateral: Double?): Double? {
        val behind = offsetAt(trace, progress - TrajectoryWindowMeters)
        val ahead = offsetAt(trace, progress + TrajectoryWindowMeters)
        return when {
            behind != null && ahead != null -> (ahead - behind) / (2 * TrajectoryWindowMeters)
            lateral == null -> null
            behind != null -> (lateral - behind) / TrajectoryWindowMeters
            ahead != null -> (ahead - lateral) / TrajectoryWindowMeters
            else -> null
        }
    }

    /** Speed in km/h: the speed channel, else from progress along the axis. */
    private fun speedAt(trace: List<ProgressSegment>, time: Double): Double? {
        val factor = metresPerSecond
        val measured = if (speedName != null && factor != null) session.valueAt(Aliases.SPEED, time) else null
        if (measured != null) return measured * factor!! * KmhPerMetrePerSecond
        val a = progressAtTime(trace, time - SpeedFromProgressSeconds)
        val b = progressAtTime(trace, time + SpeedFromProgressSeconds)
        return if (a != null && b != null) (b - a) / (2 * SpeedFromProgressSeconds) * KmhPerMetrePerSecond else null
    }

    private fun offsetAt(trace: List<ProgressSegment>, progress: Double): Double? =
        progress.takeIf { it in 0.0..axis.lengthMeters }
            ?.let { timeAtProgress(trace, it) }
            ?.let { lateralAt(it, progress) }

    private fun lateralAt(time: Double, progress: Double): Double? {
        val latitude = session.valueAt(Aliases.LATITUDE, time)
        val longitude = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude)
        if (latitude == null || longitude == null) return null
        val at = progress.coerceIn(0.0, axis.lengthMeters)
        return lateralOffsetMeters(axis, at, projectCoordinate(GeoCoordinate(latitude, longitude), axis.origin))
    }
}

private const val KmhPerMetrePerSecond = 3.6
private const val SpeedFromProgressSeconds = 0.25
