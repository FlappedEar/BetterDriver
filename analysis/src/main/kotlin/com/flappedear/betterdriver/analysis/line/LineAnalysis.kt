package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.measureCornerLap
import com.flappedear.betterdriver.analysis.corners.lateralOffsetMeters
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.optimal.AcceleratingAboveG
import com.flappedear.betterdriver.analysis.optimal.AccelerationWindowSeconds
import com.flappedear.betterdriver.analysis.optimal.BrakingBelowG
import com.flappedear.betterdriver.analysis.optimal.JointThresholds
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.SegmentReviewSmoothingMeters
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.timeOnAxis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// Where on the track the time is and what to do there (FBBD-306): each corner of a session's
// fastest lap against the driver's own fastest pass through that corner, from this session or
// any other at the same track. The target is a line he has driven, never a simulated one: the
// app does not know where the track's edges are, only where his laps went.

/** Line points and the road band are read every this many metres along the axis. */
const val LineStepMeters = 2.0

/** The road band needs this many laps through a point; fewer say little about where the road is. */
const val MinimumBandLaps = 3

/**
 * Differences smaller than these are not advice: the receiver repeats them lap to lap.
 * [lineMeters] is [JointThresholds.lateralMeters]. [placeMeters]: at 10 Hz a car doing 100 km/h
 * moves 2.8 m between fixes. [speedKmh]: a GNSS speed repeats to a few tenths of a km/h, but a
 * minimum speed moves by about a km/h lap to lap without the driver doing anything different.
 */
data class LineThresholds(
    val lineMeters: Double = JointThresholds().lateralMeters,
    val placeMeters: Double = 3.0,
    val speedKmh: Double = 1.0,
)

/** Another session at the same track whose laps can be the target (read in full, like PreviousLaps). */
data class LineSession(val session: TelemetrySession, val laps: LapSession, val label: String)

/** One point of a lap's line, in metres east and north of the axis origin. */
data class LinePoint(
    /** Distance from the start/finish line on the session's axis. */
    val progressMeters: Double,
    val eastMeters: Double,
    val northMeters: Double,
    /** Distance from the axis (the fastest lap's line), positive left of travel. */
    val offsetMeters: Double?,
    val speedKmh: Double?,
    /** From the change of speed over [AccelerationWindowSeconds] either side, as RealisticOptimal reads it. */
    val pedal: PedalState?,
)

/** One lap through one corner's window: its time there, where it braked, turned and accelerated. */
data class LinePass(
    /** [LineSession.label] of the session the lap is from; empty for the analysed session. */
    val source: String,
    val lapNumber: Int,
    val lapSeconds: Double,
    /** Through the corner's window. */
    val seconds: Double,
    /** Where braking starts (Corner Analyzer), metres along the axis. */
    val brakingPointMeters: Double?,
    /** Where the lap is slowest in the corner, and how slow. */
    val minimumSpeedMeters: Double?,
    val minimumSpeedKmh: Double?,
    /** Where the throttle goes back on (measured pedal, or inferred from acceleration). */
    val pickupMeters: Double?,
    /** Distance from the axis at the corner's apex (mid-corner without one), positive left. */
    val apexOffsetMeters: Double?,
    val line: List<LinePoint>,
)

/** Where the laps went at one point: the road they used, at least, though not necessarily all of it. */
data class RoadBand(
    val progressMeters: Double,
    /** The furthest left and right any lap went, from the axis, positive left. */
    val leftMeters: Double,
    val rightMeters: Double,
    val left: MetricPoint,
    val right: MetricPoint,
)

/**
 * What the target does differently, null where the difference is within [LineThresholds].
 * Along-track values are target minus reference: positive is later.
 */
data class CornerAdvice(
    val brakeMeters: Double? = null,
    /** Where the slowest point is. */
    val apexMeters: Double? = null,
    /** Line at the apex: positive is tighter (toward the inside of the turn), negative wider. */
    val tighterMeters: Double? = null,
    /** Slowest speed: positive carries more. */
    val minimumSpeedKmh: Double? = null,
    /** Back on throttle. */
    val pickupMeters: Double? = null,
) {
    val any: Boolean
        get() = listOf(brakeMeters, apexMeters, tighterMeters, minimumSpeedKmh, pickupMeters).any { it != null }
}

/**
 * One corner: its window (from the middle of the straight before it to the middle of the one
 * after, so the braking and the exit count, and the windows tile the lap), the reference lap and
 * the fastest pass through the window, and what that pass does differently.
 */
data class CornerLine(
    val segmentId: String,
    val name: String,
    val turnsLeft: Boolean,
    val startMeters: Double,
    /** Below [startMeters] when the window crosses the start/finish line. */
    val endMeters: Double,
    val reference: LinePass,
    val target: LinePass,
    /** Reference minus target through the window; 0 when the reference lap is the fastest there. */
    val gainSeconds: Double,
    val road: List<RoadBand>,
    val advice: CornerAdvice,
)

/** A session's fastest lap against the fastest pass through each corner of every lap at the track. */
data class LineAnalysis(
    val referenceLapNumber: Int,
    val referenceLapSeconds: Double,
    /** In track order. */
    val corners: List<CornerLine>,
    /** Eligible laps compared, over [sessions] sessions. */
    val laps: Int,
    val sessions: Int,
) {
    /** Most time to find first. */
    val ranked: List<CornerLine> get() = corners.sortedByDescending { it.gainSeconds }

    /** The windows tile the lap, so their gains add up without counting any stretch twice. */
    val totalGainSeconds: Double get() = corners.sumOf { it.gainSeconds }
}

/**
 * [analysis] of [session] against the laps of [others] at the same track, timed on its axis and
 * corners as the session before is for the summary. Null without corners, or when the session
 * has no fastest lap.
 */
@Suppress("ReturnCount")
fun analyzeLine(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    others: List<LineSession> = emptyList(),
    thresholds: LineThresholds = LineThresholds(),
): LineAnalysis? {
    val axis = analysis.axis ?: return null
    val segments = analysis.segments ?: return null
    val referenceNumber = analysis.axisLapNumber ?: return null
    val windows = cornerWindows(segments.segments, axis)
    if (windows.isEmpty()) return null
    val features = computeTrackFeatures(axis, SegmentReviewSmoothingMeters)
    val sources = listOf(LineSession(session, analysis.laps, "")) + others
    // One reader per session, never looked up by label: labels are for display and need not be unique.
    val probes = sources.map { LineProbe(it.session, axis) }
    val candidates = sources.flatMapIndexed { k, source ->
        source.laps.timedLaps.filter { it.referenceEligible }.map { lap ->
            val population = PopulationLap(source.session, lap.startTelemetryTime, lap.endTelemetryTime, lap.number)
            Candidate(source, probes[k], population, lap.durationSeconds)
        }
    }
    val reference = candidates.firstOrNull { it.probe === probes.first() && it.lap.reference == referenceNumber }
        ?: return null
    val approved = ApprovedSegments(windows.map { it.window }, segments.revision + "/line")
    // Every lap read the same way on this session's axis (FBBD-215).
    val timed = timeOnAxis(axis, approved, candidates.map { it.lap }, analysis.robustness)
    val corners = windows.mapNotNull { w ->
        val seconds = candidates.indices.map { timed.times[it].sector(w.window.id)?.seconds }
        val ref = candidates.indexOf(reference)
        val refSeconds = seconds[ref] ?: return@mapNotNull null
        val best = seconds.indices.filter { seconds[it] != null }.minBy { seconds[it]!! }
        fun pass(i: Int): LinePass {
            val c = candidates[i]
            val measured = measureCornerLap(
                axis, features, segments, w.corner.id, w.corner.startProgressMeters, w.corner.endProgressMeters,
                timed.traces[i], c.source.session, c.lap.start, c.lap.end, c.lap.reference,
            )
            val metrics = measured.observation
            val slowest = measured.speeds.minimum.takeIf { measured.speeds.valid && it.value != null }
            return LinePass(
                c.source.label, c.lap.reference as Int, c.lapSeconds, seconds[i]!!,
                metrics.brakingPointMeters, slowest?.progressMeters,
                slowest?.value?.let { kmh(it, measured.speeds.unit) }, metrics.pickupMeters, metrics.lineOffsetMeters,
                c.probe.line(timed.traces[i], w.window, axis.lengthMeters),
            )
        }
        val refPass = pass(ref)
        val targetPass = if (best == ref) refPass else pass(best)
        val readers = candidates.indices.map { candidates[it].probe to timed.traces[it] }
        val road = roadBand(axis, w.window, readers)
        val turnsLeft = turnsLeft(features, w.corner, axis.lengthMeters)
        CornerLine(
            w.corner.id, w.corner.name, turnsLeft, w.window.startProgressMeters, w.window.endProgressMeters,
            refPass, targetPass, max(0.0, refSeconds - targetPass.seconds), road,
            if (best == ref) CornerAdvice() else advise(refPass, targetPass, turnsLeft, axis.lengthMeters, thresholds),
        )
    }
    return LineAnalysis(referenceNumber, reference.lapSeconds, corners, candidates.size, sources.size)
}

/** What [target] does differently from [reference] in a corner, beyond [thresholds]. */
fun advise(
    reference: LinePass,
    target: LinePass,
    turnsLeft: Boolean,
    axisLengthMeters: Double,
    thresholds: LineThresholds = LineThresholds(),
): CornerAdvice {
    fun place(a: Double?, b: Double?) = if (a != null && b != null) {
        signedSpan(a, b, axisLengthMeters).takeIf { abs(it) >= thresholds.placeMeters }
    } else {
        null
    }
    val tighter = if (reference.apexOffsetMeters != null && target.apexOffsetMeters != null) {
        ((target.apexOffsetMeters - reference.apexOffsetMeters) * if (turnsLeft) 1.0 else -1.0)
            .takeIf { abs(it) >= thresholds.lineMeters }
    } else {
        null
    }
    val speed = if (reference.minimumSpeedKmh != null && target.minimumSpeedKmh != null) {
        (target.minimumSpeedKmh - reference.minimumSpeedKmh).takeIf { abs(it) >= thresholds.speedKmh }
    } else {
        null
    }
    return CornerAdvice(
        brakeMeters = place(reference.brakingPointMeters, target.brakingPointMeters),
        apexMeters = place(reference.minimumSpeedMeters, target.minimumSpeedMeters),
        tighterMeters = tighter,
        minimumSpeedKmh = speed,
        pickupMeters = place(reference.pickupMeters, target.pickupMeters),
    )
}

/** [to] minus [from] along a loop of [length], the short way round; plain difference without a length. */
internal fun signedSpan(from: Double, to: Double, length: Double): Double {
    val d = to - from
    if (!(length > 0.0)) return d
    val m = d.mod(length)
    return if (m > length / 2) m - length else m
}

private class Candidate(val source: LineSession, val probe: LineProbe, val lap: PopulationLap, val lapSeconds: Double)

internal class CornerWindow(val corner: TrackSegment, val window: TrackSegment)

/**
 * Each corner widened to the middle of the straight on either side; a corner next to a corner
 * keeps the boundary between them. On a closed axis the first segment follows the last.
 */
internal fun cornerWindows(segments: List<TrackSegment>, axis: ProgressAxis): List<CornerWindow> {
    val length = axis.lengthMeters
    fun middle(s: TrackSegment): Double {
        val raw = s.endProgressMeters - s.startProgressMeters
        val span = if (axis.closed) raw.mod(length) else raw
        return if (axis.closed) (s.startProgressMeters + span / 2).mod(length) else s.startProgressMeters + span / 2
    }
    return segments.withIndex().filter { it.value.type == TrackSegmentType.Corner }.map { (i, corner) ->
        val before = segments.getOrNull(i - 1) ?: segments.last().takeIf { axis.closed && segments.size > 1 }
        val after = segments.getOrNull(i + 1) ?: segments.first().takeIf { axis.closed && segments.size > 1 }
        val start = if (before?.type == TrackSegmentType.Straight) middle(before) else corner.startProgressMeters
        val end = if (after?.type == TrackSegmentType.Straight) middle(after) else corner.endProgressMeters
        CornerWindow(corner, TrackSegment("line-" + corner.id, corner.name, TrackSegmentType.Corner, start, end))
    }
}

/** Progress values every [LineStepMeters] through [window], wrapping at the line on a loop. */
private fun stations(window: TrackSegment, length: Double): List<Double> {
    val span = (window.endProgressMeters - window.startProgressMeters).let { if (it < 0) it + length else it }
    val n = (span / LineStepMeters).toInt()
    return (0..n).map { i ->
        val p = window.startProgressMeters + i * LineStepMeters
        if (p > length) p - length else p
    }
}

/** Whether [corner] turns left overall: its mean curvature, positive left. */
private fun turnsLeft(features: TrackFeatures, corner: TrackSegment, length: Double): Boolean {
    val inside = features.samples.filter {
        val p = it.progressMeters
        if (corner.endProgressMeters >= corner.startProgressMeters) {
            p in corner.startProgressMeters..corner.endProgressMeters
        } else {
            p >= corner.startProgressMeters || p <= corner.endProgressMeters
        }
    }
    return inside.sumOf { it.curvaturePerMeter } > 0.0 && length > 0.0
}

private fun roadBand(
    axis: ProgressAxis,
    window: TrackSegment,
    laps: List<Pair<LineProbe, List<ProgressSegment>>>,
): List<RoadBand> =
    stations(window, axis.lengthMeters).mapNotNull { p ->
        val offsets = laps.mapNotNull { (probe, trace) -> timeAtProgress(trace, p)?.let { probe.offsetAt(it, p) } }
        if (offsets.size < MinimumBandLaps) {
            null
        } else {
            val left = offsets.max()
            val right = offsets.min()
            val l = beside(axis, p, left)
            val r = beside(axis, p, right)
            if (l == null || r == null) null else RoadBand(p, left, right, l, r)
        }
    }

/** The point [offset] metres left of [axis] at [progress] (negative: right). */
@Suppress("ReturnCount")
internal fun beside(axis: ProgressAxis, progress: Double, offset: Double): MetricPoint? {
    if (axis.points.size < 2 || axis.cumulative.size != axis.points.size) return null
    var upper = 0
    var high = axis.cumulative.size
    while (upper < high) {
        val middle = (upper + high) shr 1
        if (axis.cumulative[middle] < progress) upper = middle + 1 else high = middle
    }
    // A loop's length includes the stretch from its last point back to its first.
    val tail = axis.closed && progress > axis.cumulative.last()
    val index = upper.coerceIn(1, axis.points.size - 1)
    val a = if (tail) axis.points.last() else axis.points[index - 1]
    val b = if (tail) axis.points.first() else axis.points[index]
    val from = if (tail) axis.cumulative.last() else axis.cumulative[index - 1]
    val span = (if (tail) axis.lengthMeters else axis.cumulative[index]) - from
    val f = if (span > 0.0) ((progress - from) / span).coerceIn(0.0, 1.0) else 0.0
    val east = b.eastMeters - a.eastMeters
    val north = b.northMeters - a.northMeters
    val length = sqrt(east * east + north * north)
    if (length <= 0.0) return null
    return MetricPoint(
        a.eastMeters + east * f - north / length * offset,
        a.northMeters + north * f + east / length * offset,
    )
}

private fun kmh(value: Double, unit: String): Double? =
    metresPerSecondPerSpeedUnit(unit)?.let { value * it * KmhPerMetrePerSecond }

/** Reads a lap's position, line, speed and pedals from one session's channels. */
private class LineProbe(private val session: TelemetrySession, private val axis: ProgressAxis) {
    private val speedName = session.aliases[Aliases.SPEED]
    private val metresPerSecond = speedName?.let { metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, it)) }

    fun line(trace: List<ProgressSegment>, window: TrackSegment, length: Double): List<LinePoint> =
        stations(window, length).mapNotNull { p ->
            val time = timeAtProgress(trace, p) ?: return@mapNotNull null
            val at = position(time) ?: return@mapNotNull null
            LinePoint(p, at.eastMeters, at.northMeters, offset(at, p), speedAt(time), pedalAt(time))
        }

    fun offsetAt(time: Double, progress: Double): Double? = position(time)?.let { offset(it, progress) }

    private fun offset(at: MetricPoint, progress: Double) =
        lateralOffsetMeters(axis, progress.coerceIn(0.0, axis.lengthMeters), at)

    @Suppress("ReturnCount")
    private fun position(time: Double): MetricPoint? {
        val latitude = session.valueAt(Aliases.LATITUDE, time) ?: return null
        val longitude = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude) ?: return null
        return projectCoordinate(GeoCoordinate(latitude, longitude), axis.origin)
    }

    private fun speedAt(time: Double): Double? {
        val factor = metresPerSecond ?: return null
        return session.valueAt(Aliases.SPEED, time)?.let { it * factor * KmhPerMetrePerSecond }
    }

    @Suppress("ReturnCount")
    private fun pedalAt(time: Double): PedalState? {
        val before = speedAt(time - AccelerationWindowSeconds) ?: return null
        val after = speedAt(time + AccelerationWindowSeconds) ?: return null
        val g = (after - before) / KmhPerMetrePerSecond / (2 * AccelerationWindowSeconds) / StandardGravity
        return when {
            g < BrakingBelowG -> PedalState.Braking
            g > AcceleratingAboveG -> PedalState.Accelerating
            else -> PedalState.Coasting
        }
    }
}

private const val KmhPerMetrePerSecond = 3.6
private const val StandardGravity = 9.80665
