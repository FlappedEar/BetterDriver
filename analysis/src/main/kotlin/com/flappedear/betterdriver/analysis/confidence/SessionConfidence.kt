package com.flappedear.betterdriver.analysis.confidence

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.isValidCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.quality.provenanceText
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.recorder.Confidence
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

// How far each lap's positions through each corner and straight can be trusted (FBBD-177): the
// detectors run on every timed lap in every segment, on the segment's own geometry, and on what
// applies to the whole session (where positions come from, their accuracy, the corner identity).
// The conclusions drawn from them (Conclusions.kt) take their confidence from here.

/** A GPS accuracy (median, metres) worse than this leaves conclusions Medium. */
const val ModerateAccuracyMeters = 2.0

/** ... and worse than this, Low. */
const val PoorAccuracyMeters = 5.0

/** A lap this far from the median time through a segment, in interquartile ranges, is anomalous there ... */
const val OutlierInterquartileRanges = 3.0

/** ... and at least this far, seconds. */
const val OutlierFloorSeconds = 0.3

/** Fewer laps than this give no lap-to-lap statistics for finding an anomalous one. */
private const val OutlierMinimumLaps = 4

/** Reason codes of what applies to a whole session or segment. */
object SessionCodes {
    const val POSITION_PROVENANCE = "position_provenance"
    const val ACCURACY = "gps_accuracy"
    const val IDENTITY = "corner_identity"
    const val ANOMALOUS_LAP = "anomalous_lap"
}

/**
 * One segment's evidence: its own geometry ([adjacentTrack]) and each timed lap's positions through
 * it, in [com.flappedear.betterdriver.analysis.laps.LapSession.timedLaps] order, each reason naming its lap.
 */
data class SegmentConfidence(
    val segmentId: String,
    val geometry: Graded = Graded.High,
    val laps: List<Graded> = emptyList(),
)

/**
 * How far an analysed session's conclusions can be trusted (FBBD-177): [session] applies to all of
 * them, [segments] (by segment id) to those drawn in one corner or straight.
 */
data class SessionConfidence(
    val session: Graded = Graded.High,
    val segments: Map<String, SegmentConfidence> = emptyMap(),
) {
    /** Timed lap [index] through [segmentId]: the session's, the segment's and the lap's own evidence. */
    fun lap(segmentId: String, index: Int): Graded = segment(segmentId) + lapOnly(segmentId, index)

    /** What applies to every lap through [segmentId]: the session's and the segment's own evidence. */
    fun segment(segmentId: String): Graded = session + (segments[segmentId]?.geometry ?: Graded.High)

    /** Timed lap [index]'s own evidence through [segmentId]. */
    fun lapOnly(segmentId: String, index: Int): Graded =
        segments[segmentId]?.laps?.getOrNull(index) ?: Graded.High
}

/** The confidence of [analysis] of [session]: every segment on every timed lap, and the session as a whole. */
fun assessConfidence(session: TelemetrySession, analysis: SessionAnalysis): SessionConfidence {
    val whole = sessionEvidence(session, analysis)
    val axis = analysis.axis
    val segments = analysis.segments?.segments.orEmpty()
    if (axis == null || !axis.valid || segments.isEmpty()) return SessionConfidence(whole)
    val positions = Positions.of(session, axis)
    val laps = analysis.laps.timedLaps
    val assessed = segments.associate { segment ->
        val legs = hairpinLegs(axis, segment.startProgressMeters, segment.endProgressMeters)
        val graded = laps.indices.map { i ->
            val lap = laps[i]
            val trace = analysis.traces.getOrNull(i).orEmpty()
            val sector = analysis.lapTimes.getOrNull(i)?.sector(segment.id)
            val found = positions?.let { lapEvidence(axis, it, segment, lap, trace, sector, legs) }.orEmpty()
            Graded.of(found.map { it.copy(text = "lap ${lap.number}: ${it.text}") })
        }
        segment.id to SegmentConfidence(
            segment.id,
            Graded.of(adjacentTrack(axis, segment.startProgressMeters, segment.endProgressMeters)),
            anomalous(analysis, segment.id, graded),
        )
    }
    return SessionConfidence(whole, assessed)
}

/** What applies to the whole session: where positions come from, how accurate they are, the corner identity. */
private fun sessionEvidence(session: TelemetrySession, analysis: SessionAnalysis): Graded {
    val reasons = mutableListOf<ConfidenceReason>()
    analysis.provenance.position?.takeIf { !it.measured }?.let {
        val text = "positions ${provenanceText(it)}"
        reasons += ConfidenceReason(SessionCodes.POSITION_PROVENANCE, Confidence.MEDIUM, text)
    }
    session.channel(Aliases.ACCURACY)?.values?.filter { it.isFinite() && it > 0.0 }?.takeIf { it.isNotEmpty() }
        ?.let { median(it) }
        ?.let { accuracy ->
            val cap = when {
                accuracy > PoorAccuracyMeters -> Confidence.LOW
                accuracy > ModerateAccuracyMeters -> Confidence.MEDIUM
                else -> null
            }
            cap?.let {
                reasons += ConfidenceReason(
                    SessionCodes.ACCURACY, it, String.format(Locale.ROOT, "GPS accuracy %.1f m", accuracy),
                )
            }
        }
    if (analysis.segments != null && !analysis.identified) {
        reasons += ConfidenceReason(
            SessionCodes.IDENTITY, Confidence.MEDIUM, "corners not matched to the track's lasting corners",
        )
    }
    return Graded.of(reasons)
}

/**
 * [graded] with every eligible lap whose time through [segmentId] is far from the other laps' (an
 * anomalous lap) marked Low there: advice never rests on a single anomalous lap.
 */
private fun anomalous(analysis: SessionAnalysis, segmentId: String, graded: List<Graded>): List<Graded> {
    val laps = analysis.laps.timedLaps
    val times = laps.indices.associateWith { i ->
        analysis.lapTimes.getOrNull(i)?.sector(segmentId)?.seconds
            ?.takeIf { laps[i].referenceEligible && graded[i].level != Confidence.LOW }
    }.filterValues { it != null }.mapValues { it.value!! }
    val summary = summarizeConsistency(times.values)
    val median = summary.median
    if (times.size < OutlierMinimumLaps || median == null) return graded
    val fence = maxOf(OutlierInterquartileRanges * (summary.interquartileRange ?: 0.0), OutlierFloorSeconds)
    return graded.mapIndexed { i, g ->
        val t = times[i]
        if (t == null || abs(t - median) <= fence) {
            g
        } else {
            val text = String.format(
                Locale.ROOT, "lap %d: %.2f s %s the other laps here (anomalous lap)",
                laps[i].number, abs(t - median), if (t < median) "faster than" else "slower than",
            )
            g + ConfidenceReason(SessionCodes.ANOMALOUS_LAP, Confidence.LOW, text)
        }
    }
}

/** The session's positions around the axis origin, and its speeds in m/s. */
private class Positions(
    val session: TelemetrySession,
    val latitude: TelemetryChannel,
    val axis: ProgressAxis,
    val speed: TelemetryChannel?,
    val metresPerSecond: Double?,
) {
    /** The positions from [from] to [to] (seconds), in order. */
    fun between(from: Double, to: Double): List<Pair<Double, GeoCoordinate>> {
        val times = latitude.timestamps
        val out = ArrayList<Pair<Double, GeoCoordinate>>()
        var i = lowerBound(times, from)
        while (i < times.size && times[i] <= to) {
            val t = times[i]
            val lon = session.valueAt(Aliases.LONGITUDE, t, Interpolation.Longitude)
            val c = lon?.let { GeoCoordinate(latitude.values[i], it) }
            if (c != null && usable(c)) out += t to c
            i++
        }
        return out
    }

    /** A valid position; receivers without a fix can report 0, 0. */
    private fun usable(c: GeoCoordinate) =
        isValidCoordinate(c) && !(c.latitudeDegrees == 0.0 && c.longitudeDegrees == 0.0)

    /** Speed at [t] in m/s, or null without a speed channel in a known unit. */
    fun speedAt(t: Double): Double? {
        val factor = metresPerSecond ?: return null
        return speed?.valueAt(t)?.let { it * factor }
    }

    companion object {
        fun of(session: TelemetrySession, axis: ProgressAxis): Positions? {
            val latitude = session.channel(Aliases.LATITUDE) ?: return null
            val speed = session.channel(Aliases.SPEED)
            val factor = speed?.let { metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, it.name)) }
            return Positions(session, latitude, axis, speed, factor)
        }
    }
}

/** A lap's projected progress, by time, bridging the breaks between its segments. */
private class LapProgress(trace: List<ProgressSegment>) {
    private val samples: List<ProjectedSample> = trace.flatMap { it.samples }.sortedBy { it.telemetryTime }

    /** The first time progress reaches [meters], interpolated; null if it never does. */
    fun timeAt(meters: Double): Double? {
        val i = samples.indexOfFirst { it.progressMeters >= meters }
        return when {
            i < 0 -> null
            i == 0 -> samples[0].telemetryTime.takeIf { samples[0].progressMeters - meters <= EdgeMeters }
            else -> {
                val a = samples[i - 1]
                val b = samples[i]
                val span = b.progressMeters - a.progressMeters
                val u = if (span <= 0.0) 0.0 else (meters - a.progressMeters) / span
                a.telemetryTime + u * (b.telemetryTime - a.telemetryTime)
            }
        }
    }

    private val times = DoubleArray(samples.size) { samples[it].telemetryTime }

    /** Progress at [time], interpolated; null outside the projected samples. */
    fun progressAt(time: Double): Double? {
        val i = lowerBound(times, time)
        return when {
            samples.isEmpty() || time < times.first() || time > times.last() -> null
            i == 0 -> samples[0].progressMeters
            else -> {
                val a = samples[i - 1]
                val b = samples[i]
                val span = b.telemetryTime - a.telemetryTime
                val u = if (span <= 0.0) 0.0 else (time - a.telemetryTime) / span
                a.progressMeters + u * (b.progressMeters - a.progressMeters)
            }
        }
    }

    /** Projected samples from [from] to [to]. */
    fun placed(from: Double, to: Double): Int = samples.count { it.telemetryTime in from..to }

    private companion object {
        /** A lap whose first placed position is this close past a boundary still starts at it. */
        const val EdgeMeters = 15.0
    }
}

/**
 * When lap [lap] is in [segment]: one span of time, or two for a segment across the line (the end of
 * the lap and its start). The sector's own boundary times when it has them, else from the lap's
 * progress. Null when the lap was never tracked to one of its boundaries.
 */
private fun spans(
    axis: ProgressAxis,
    segment: TrackSegment,
    lap: TimedLap,
    progress: LapProgress,
    sector: SectorTime?,
): List<ClosedFloatingPointRange<Double>>? {
    val start = sector?.startTime ?: progress.timeAt(segment.startProgressMeters)
        ?: lap.startTelemetryTime.takeIf { segment.startProgressMeters <= 0.0 }
    val end = sector?.endTime ?: progress.timeAt(segment.endProgressMeters)
        ?: lap.endTelemetryTime.takeIf { segment.endProgressMeters >= axis.lengthMeters }
    if (start == null || end == null) return null
    return if (start <= end) {
        listOf(start..end)
    } else {
        // Across the line: from the lap's start to the segment's end, and from its start to the lap's end.
        listOf(lap.startTelemetryTime..end, start..lap.endTelemetryTime)
    }
}

/** What the detectors find in [lap]'s positions through [segment]; [legs]: the segment is a hairpin. */
@Suppress("LongParameterList")
private fun lapEvidence(
    axis: ProgressAxis,
    positions: Positions,
    segment: TrackSegment,
    lap: TimedLap,
    trace: List<ProgressSegment>,
    sector: SectorTime?,
    legs: Boolean,
): List<ConfidenceReason> {
    val progress = LapProgress(trace)
    val spans = spans(axis, segment, lap, progress, sector)
        ?: return listOf(ConfidenceReason(Codes.NOT_COVERED, Confidence.LOW, "not tracked through it"))
    val start = segment.startProgressMeters
    val end = segment.endProgressMeters
    val window = AxisWindow(axis, start, end, AdjacentMarginMeters)
    val reasons = mutableListOf<ConfidenceReason?>()
    val fixes = mutableListOf<LapFix>()
    var placed = 0
    for (span in spans) {
        val here = positions.between(span.start, span.endInclusive)
            .map { (t, c) -> LapFix(t, projectCoordinate(c, axis.origin), progress.progressAt(t)) }
        val interval = positions.latitude.baseIntervalSeconds
        reasons += gnssGap(freshTimes(here), span.start, span.endInclusive, interval)
        placed += progress.placed(span.start, span.endInclusive)
        fixes += here
    }
    reasons += projectionAmbiguity(fixes.size, placed)
    reasons += weakInterpolation(fixes.map { it.time })
    reasons += hairpinCrossover(axis, start, end, fixes, legs)
    reasons += poorHeading(axis, start, end, fixes)
    reasons += veryLowSpeed(fixes.mapNotNull { positions.speedAt(it.time) }.minOrNull())
    val offsets = fixes.mapNotNull { f ->
        window.nearest(f.point)?.let { i ->
            val p = axis.points[i]
            hypot(f.point.eastMeters - p.eastMeters, f.point.northMeters - p.northMeters)
        }
    }
    reasons += pitLane(offsets)
    reasons += differentLine(offsets)
    return reasons.filterNotNull()
}
