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
import com.flappedear.betterdriver.analysis.focus.FocusMinimumLossSeconds
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
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.analysis.vehicle.vehicleChannel
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.SteeringPass
import com.flappedear.betterdriver.timing.steering.steeringAdvice
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// Where on the track the time is and what to do there (FBBD-306): each corner of a session's
// fastest lap against the driver's own target pass through that corner (his second fastest, a
// time driven twice), from this session or any other at the same track. The target is a line he
// has driven, never a simulated one: the app does not know where the track's edges are, only
// where his laps went.

/** Line points and the road band are read every this many metres along the axis. */
const val LineStepMeters = 2.0

/** The road band needs this many laps through a point; fewer say little about where the road is. */
const val MinimumBandLaps = 3

/**
 * The target through a corner is the [ConfirmingPasses]-th fastest pass over every lap at the track:
 * a time driven at least twice, the rule the coach's targets follow (FBBD-177). A corner with fewer
 * passes than that has no target.
 */
const val ConfirmingPasses = 2

/**
 * Differences smaller than these are not advice: the receiver repeats them lap to lap.
 * [lineMeters] is [JointThresholds.lateralMeters]. A corner with less than [gainSeconds] to find
 * has nothing to find. [placeMeters]: at 10 Hz a car doing 100 km/h moves 2.8 m between
 * fixes. [speedKmh]: a GNSS speed repeats to a few tenths of a km/h, but a
 * minimum speed moves by about a km/h lap to lap without the driver doing anything different.
 */
data class LineThresholds(
    val lineMeters: Double = JointThresholds().lateralMeters,
    val placeMeters: Double = 3.0,
    val speedKmh: Double = 1.0,
    /** Less time than this through a corner is GPS timing noise, as for the session focus (FBBD-20). */
    val gainSeconds: Double = FocusMinimumLossSeconds,
)

/**
 * Another session at the same track whose laps can be the target (read in full, like PreviousLaps).
 * [label] is what the screen calls it; [id] is its session id, for the line plan.
 */
data class LineSession(val session: TelemetrySession, val laps: LapSession, val label: String, val id: String = "")

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
    /** The steering wheel in degrees, positive turning left (FBBD-321); null without the car's steering angle. */
    val steerDeg: Double? = null,
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
    /** [LineSession.id] of the session the lap is from. */
    val sourceId: String = "",
    /** What the hands did through the window (FBBD-321); null without the car's steering angle. */
    val steering: SteeringPass? = null,
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
    /**
     * What to do with the wheel (FBBD-321), in driving order; empty without steering on both passes.
     */
    val steering: List<SteerAdvice> = emptyList(),
) {
    val any: Boolean
        get() = listOf(brakeMeters, apexMeters, tighterMeters, minimumSpeedKmh, pickupMeters).any { it != null } ||
            steering.isNotEmpty()
}

/**
 * One corner: its window (from the middle of the straight before it to the middle of the one
 * after, so the braking and the exit count, and the windows tile the lap), the reference lap and
 * the target pass through the window ([ConfirmingPasses]), and what that pass does differently.
 * [segmentId] is the track's CornerId when the analysis has the track's corner identity (FBBD-172).
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
    /** Reference minus target through the window; 0 within [LineThresholds.gainSeconds] of it. */
    val gainSeconds: Double,
    val road: List<RoadBand>,
    val advice: CornerAdvice,
    /** The analysed session's fastest pass through the window, from any of its laps (FBBD-310). */
    val sessionBestSeconds: Double? = null,
)

/** A session's fastest lap against the target pass through each corner of every lap at the track. */
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
 *
 * [others] is read one session at a time and only what the target needs is kept of each (its two
 * fastest passes through each corner and how wide its laps went), so a sequence that reads each
 * session as it is asked for never holds more than one in memory besides [session].
 */
@Suppress("ReturnCount")
fun analyzeLine(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    others: Sequence<LineSession> = emptySequence(),
    thresholds: LineThresholds = LineThresholds(),
): LineAnalysis? {
    val axis = analysis.axis ?: return null
    val segments = analysis.segments ?: return null
    val referenceNumber = analysis.axisLapNumber ?: return null
    val windows = cornerWindows(segments.segments, axis)
    if (windows.isEmpty()) return null
    val reader = LineReader(analysis, axis, segments.revision, windows)
    val own = reader.read(LineSession(session, analysis.laps, ""), referenceNumber) ?: return null
    val read = mutableListOf(own)
    for (other in others) reader.read(other, null)?.let { read += it }
    return lineOf(reader, read, thresholds)
}

/**
 * The line analysis of [read] (the analysed session first, with its reference lap, then the others)
 * on [reader]'s axis and windows. Null when the first has no reference lap.
 */
internal fun lineOf(reader: LineReader, read: List<SessionLines>, thresholds: LineThresholds): LineAnalysis? {
    val own = read.first()
    val reference = own.reference ?: return null
    val corners = reader.windows.indices.mapNotNull { k ->
        val w = reader.windows[k]
        val refPass = reference.passes[k] ?: return@mapNotNull null
        // The target is a time driven twice (FBBD-177), as the coach's: the second fastest pass.
        val second = read.flatMap { it.fastest[k] }.sortedBy { it.seconds }.getOrNull(ConfirmingPasses - 1)
        val ahead = second != null && refPass.seconds - second.seconds >= thresholds.gainSeconds
        val target = if (ahead) second!! else refPass
        val turnsLeft = reader.turnsLeft(k)
        CornerLine(
            w.corner.id, w.corner.name, turnsLeft, w.window.startProgressMeters, w.window.endProgressMeters,
            refPass, target, max(0.0, refPass.seconds - target.seconds), reader.band(k, read.map { it.road[k] }),
            if (ahead) advise(refPass, target, turnsLeft, reader.axis.lengthMeters, thresholds) else CornerAdvice(),
            own.fastest[k].firstOrNull()?.seconds,
        )
    }
    return LineAnalysis(reference.number, reference.lapSeconds, corners, read.sumOf { it.laps }, read.size)
}

/** Overload for a list of sessions already read. */
fun analyzeLine(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    others: List<LineSession>,
    thresholds: LineThresholds = LineThresholds(),
): LineAnalysis? = analyzeLine(analysis, session, others.asSequence(), thresholds)

/** What one session gives the line analysis; its samples are not kept. */
internal class SessionLines(
    /** Per window, its fastest passes (at most [ConfirmingPasses]), fastest first. */
    val fastest: List<List<LinePass>>,
    /** Per window, how far left and right its laps went at each station. */
    val road: List<RoadSpread>,
    /** The analysed session's reference lap through each window; null for other sessions. */
    val reference: ReferenceLap?,
    val laps: Int,
    /** What a day's analysis reads besides (FBBD-315); null when it was not asked for. */
    val pace: PaceLaps? = null,
)

internal class ReferenceLap(val number: Int, val lapSeconds: Double, val passes: List<LinePass?>)

/**
 * Every eligible lap of a session as a day's analysis reads it (FBBD-315): its time, its time
 * through each window, and per window the pass at the median of the laps at pace (no slower than
 * the limit the reader was given).
 */
internal class PaceLaps(
    val lapSeconds: List<Double>,
    /** Per window, per lap (as [lapSeconds]), the time through it; null where it was not timed. */
    val seconds: List<List<Double?>>,
    val median: List<LinePass?>,
)

/** The furthest left and right, and how many laps, at each station of one window. */
internal class RoadSpread(val left: DoubleArray, val right: DoubleArray, val count: IntArray)

/** Reads sessions on the analysed session's axis and corners, one at a time. */
internal class LineReader(
    private val analysis: SessionAnalysis,
    val axis: ProgressAxis,
    revision: String,
    val windows: List<CornerWindow>,
) {
    val features: TrackFeatures = computeTrackFeatures(axis, SegmentReviewSmoothingMeters)
    private val approved = ApprovedSegments(windows.map { it.window }, "$revision/line")
    private val stations = windows.map { stations(it.window, axis.lengthMeters) }

    /** Whether window [k]'s corner turns left. */
    fun turnsLeft(k: Int): Boolean = turnsLeft(features, windows[k].corner, axis.lengthMeters)

    /**
     * [source]'s eligible laps; with [referenceNumber], its reference lap too. Null without one.
     * With [paceLimitSeconds], every lap's times as well ([PaceLaps]).
     */
    @Suppress("ReturnCount", "LongMethod")
    fun read(source: LineSession, referenceNumber: Int?, paceLimitSeconds: Double? = null): SessionLines? {
        val laps = source.laps.timedLaps.filter { it.referenceEligible }
        if (laps.isEmpty()) return null
        val probe = LineProbe(source.session, axis)
        val population = laps.map {
            PopulationLap(source.session, it.startTelemetryTime, it.endTelemetryTime, it.number)
        }
        // Every lap read the same way on this session's axis (FBBD-215).
        val timed = timeOnAxis(axis, approved, population, analysis.robustness)
        fun pass(k: Int, i: Int, seconds: Double): LinePass {
            val w = windows[k]
            val lap = population[i]
            val measured = measureCornerLap(
                axis, features, analysis.segments!!, w.corner.id, w.corner.startProgressMeters,
                w.corner.endProgressMeters, timed.traces[i], source.session, lap.start, lap.end, lap.reference,
            )
            val slowest = measured.speeds.minimum.takeIf { measured.speeds.valid && it.value != null }
            val line = probe.line(timed.traces[i], w.window, axis.lengthMeters)
            return LinePass(
                source.label, lap.reference as Int, laps[i].durationSeconds, seconds,
                measured.observation.brakingPointMeters, slowest?.progressMeters,
                slowest?.value?.let { kmh(it, measured.speeds.unit) }, measured.observation.pickupMeters,
                measured.observation.lineOffsetMeters, line, source.id,
                steeringOf(line, turnsLeft(k), axis.lengthMeters),
            )
        }
        val seconds = windows.map { w -> laps.indices.map { timed.times[it].sector(w.window.id)?.seconds } }
        val fastest = windows.indices.map { k ->
            laps.indices.filter { seconds[k][it] != null }.sortedBy { seconds[k][it]!! }
                .take(ConfirmingPasses).map { pass(k, it, seconds[k][it]!!) }
        }
        val reference = referenceNumber?.let { n ->
            val i = laps.indexOfFirst { it.number == n }.takeIf { it >= 0 } ?: return null
            ReferenceLap(n, laps[i].durationSeconds, windows.indices.map { k -> seconds[k][i]?.let { pass(k, i, it) } })
        }
        val road = windows.indices.map { k ->
            val at = stations[k]
            val spread = RoadSpread(
                DoubleArray(at.size) { Double.NEGATIVE_INFINITY },
                DoubleArray(at.size) { Double.POSITIVE_INFINITY },
                IntArray(at.size),
            )
            for (trace in timed.traces) {
                at.forEachIndexed { j, p ->
                    val offset = timeAtProgress(trace, p)?.let { probe.offsetAt(it, p) } ?: return@forEachIndexed
                    spread.left[j] = max(spread.left[j], offset)
                    spread.right[j] = minOf(spread.right[j], offset)
                    spread.count[j]++
                }
            }
            spread
        }
        val pace = paceLimitSeconds?.let { limit ->
            val median = windows.indices.map { k ->
                val timed = laps.indices.filter { laps[it].durationSeconds <= limit && seconds[k][it] != null }
                    .sortedBy { seconds[k][it]!! }
                timed.getOrNull((timed.size - 1) / 2)?.let { pass(k, it, seconds[k][it]!!) }
            }
            PaceLaps(laps.map { it.durationSeconds }, seconds, median)
        }
        return SessionLines(fastest, road, reference, laps.size, pace)
    }

    /** The road the laps of every session used through window [k], where at least [MinimumBandLaps] went. */
    fun band(k: Int, spreads: List<RoadSpread>): List<RoadBand> = stations[k].indices.mapNotNull { j ->
        if (spreads.sumOf { it.count[j] } < MinimumBandLaps) return@mapNotNull null
        val left = spreads.filter { it.count[j] > 0 }.maxOf { it.left[j] }
        val right = spreads.filter { it.count[j] > 0 }.minOf { it.right[j] }
        val p = stations[k][j]
        val l = beside(axis, p, left)
        val r = beside(axis, p, right)
        if (l == null || r == null) null else RoadBand(p, left, right, l, r)
    }
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
    val tighterBy = if (reference.apexOffsetMeters != null && target.apexOffsetMeters != null) {
        (target.apexOffsetMeters - reference.apexOffsetMeters) * if (turnsLeft) 1.0 else -1.0
    } else {
        null
    }
    val tighter = tighterBy?.takeIf { abs(it) >= thresholds.lineMeters }
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
        steering = if (reference.steering != null && target.steering != null) {
            steeringAdvice(reference.steering, target.steering, tighterBy)
        } else {
            emptyList()
        },
    )
}

/** [to] minus [from] along a loop of [length], the short way round; plain difference without a length. */
internal fun signedSpan(from: Double, to: Double, length: Double): Double {
    val d = to - from
    if (!(length > 0.0)) return d
    val m = d.mod(length)
    return if (m > length / 2) m - length else m
}

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
            LinePoint(p, at.eastMeters, at.northMeters, offset(at, p), speedAt(time), pedalAt(time), steerAt(time))
        }

    fun offsetAt(time: Double, progress: Double): Double? = position(time)?.let { offset(it, progress) }

    private val steering = vehicleChannel(session, VehicleChannel.SteeringAngle)

    /** The wheel at [time]; a gap of the adapter's longer than [MaxSteeringGapSeconds] is not bridged. */
    private fun steerAt(time: Double): Double? = steering?.valueAt(time, minimumGapSeconds = MaxSteeringGapSeconds)

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
