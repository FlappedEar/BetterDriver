package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.corners.sameLap
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.map.DriveMap
import com.flappedear.betterdriver.analysis.map.driveMap
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.feature.analysis.AnalysisUi
import com.flappedear.betterdriver.feature.analysis.LineUi
import com.flappedear.betterdriver.feature.analysis.analysisUi
import com.flappedear.betterdriver.feature.analysis.defaultComparedLap
import com.flappedear.betterdriver.feature.analysis.lineUi
import com.flappedear.betterdriver.feature.analysis.withCar
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track

import com.flappedear.betterdriver.timing.track.trackAlong

/** The saved track id a session's "track" event names ("saved <id> rev <n> <name>"), or null. */
fun savedTrackId(events: List<RecorderEvent>): String? {
    val words = events.firstOrNull { it.kind == "track" }?.text?.split(' ') ?: return null
    return words.getOrNull(1)?.takeIf { words.first() == "saved" }
}

/**
 * The track whose start/finish line [data] is analysed against: the saved track the session
 * recorded when it started, else the nearest saved track with a line whose radius reaches the
 * session's first position, else the first such track the session drove into (it started away
 * from the track, or the track was made from it afterwards, FBBD-151). The track's current lines
 * are used, so a line set after the session still works. Android and iOS pick it the same way.
 */
@Suppress("ReturnCount")
fun trackFor(data: SessionData, saved: List<Track>): Track? {
    val withLine = saved.filter { it.timeable }
    savedTrackId(data.events)?.let { id -> withLine.firstOrNull { it.id == id }?.let { return it } }
    return trackAlong(sessionPositions(data), withLine)
}

/** The session's positions in time order; fixes a receiver reports without a position are left out. */
fun sessionPositions(data: SessionData): Sequence<GeoPoint> = sessionFixes(data).asSequence()
    .mapNotNull { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }

/** The session's fixes in time order. */
fun sessionFixes(data: SessionData): List<Fix> =
    data.sampleSets.sortedBy { it.tMicros }.mapNotNull { it.toFix() }

/** One timed lap as the review lists it. */
data class ReviewLap(
    val number: Int,
    val seconds: Double,
    /** Behind the fastest lap, in seconds; 0 for the fastest and for laps that do not count. */
    val deltaSeconds: Double,
    val fastest: Boolean,
    /** Whether it counts for best, theoretical best and the line (an out lap or a cut does not). */
    val counts: Boolean,
)

/** What the iPad review shows of one session (FBBD-331), worked out by the shared core as on Android. */
data class SessionReview(
    val track: Track?,
    val laps: List<ReviewLap>,
    val bestLapSeconds: Double?,
    val theoreticalBestSeconds: Double?,
    /** Where to find time (FBBD-306): the Map tab's corners, as Android shows them; null without a line. */
    val line: LineUi? = null,
)

/** A corner on the lap's distance axis, shaded behind the Compare charts; it may wrap past the line. */
data class ReviewBand(val name: String, val startMeters: Double, val endMeters: Double) {
    fun contains(meters: Double) = if (endMeters >= startMeters) {
        meters in startMeters..endMeters
    } else {
        meters >= startMeters || meters <= endMeters
    }
}

/**
 * The Compare tab (FBBD-102): lap [otherNumber] against the fastest lap [referenceNumber] by
 * distance, as Android draws it. [delta] is the other lap's time minus the fastest's (positive:
 * behind), the speeds are km/h; each list holds runs of continuous coverage.
 */
data class ReviewCompare(
    val referenceNumber: Int,
    val otherNumber: Int,
    /** Index of the other lap among the timed laps (as [SessionReview.laps]). */
    val otherIndex: Int,
    val lengthMeters: Double,
    val delta: List<List<ChartPoint>>,
    val referenceSpeed: List<List<ChartPoint>>,
    val otherSpeed: List<List<ChartPoint>>,
    val corners: List<ReviewBand>,
)

/**
 * A reviewed session: its [review], and its analysis kept so another lap can be compared without
 * analysing the session again. [linePlan] is the track's line plan made from it (FBBD-306), which
 * the app keeps for the next session's coach as Android does; null without one. [drive] is the whole
 * drive on the map with its speed and figures when the session has no timed lap (FBBD-342), as
 * Android's no-laps analysis shows it; null with laps or without positions.
 */
class ReviewedSession internal constructor(
    val review: SessionReview,
    private val analysis: SessionAnalysis?,
    private val session: TelemetrySession?,
    private val data: SessionData? = null,
    val linePlan: LinePlan? = null,
    val drive: DriveMap? = null,
) {
    /** The fastest lap against the second fastest counted lap, as Android opens Compare; or null. */
    fun defaultCompare(): ReviewCompare? = analysis?.let(::defaultComparedLap)?.let(::compare)

    /**
     * Android's analysis screen model of the session (sectors, corner details, Car tab, ...), with
     * timed lap [otherIndex] compared against the fastest; null without a counted lap.
     */
    fun screen(otherIndex: Int): AnalysisUi? = screenOf(otherIndex)

    /** As [screen], with the lap Android compares when the screen opens. */
    fun defaultScreen(): AnalysisUi? = screenOf(analysis?.let(::defaultComparedLap))

    private fun screenOf(otherIndex: Int?): AnalysisUi? {
        val analysis = analysis ?: return null
        val session = checkNotNull(session)
        val fastest = analysis.laps.fastestLapIndex
        val comparison = if (fastest != null && otherIndex != null) {
            compareLaps(analysis, session, fastest, otherIndex)
        } else {
            null
        }
        return analysisUi("", analysis, comparison)?.let { withCar(it, analysis, session, null) }
    }

    /**
     * The fastest lap against timed lap [otherIndex] on video, as Android's lap review opens on them
     * (FBBD-93): the review's laps are matched to the analysis's by their start. The video is the
     * session's clips, from its events and its clip index [indexed], with files [present] in [dir]
     * (see [LapVideoSource.of]). Null when the review does not have both laps.
     */
    fun videoReview(
        otherIndex: Int,
        indexed: List<VideoClip>,
        dir: String,
        present: (String) -> Boolean,
    ): LapVideoReview? {
        val data = data
        val track = review.track
        val fastest = analysis?.laps?.fastestLapIndex
        if (data == null || track == null || fastest == null) return null
        val laps = checkNotNull(analysis).laps.timedLaps
        // As telemetrySessionOf: telemetry time 0 is the first sample.
        val origin = data.sampleSets.minOfOrNull { it.tMicros } ?: 0L
        val traces = replayLaps(data.id, data.sampleSets, track).laps
        val starts = traces.associate { it.lap.number to it.lap.startMicros }
        fun trace(index: Int): LapTrace? = laps.getOrNull(index)
            ?.let { sameLap(sessionMicros(origin, it.startTelemetryTime), starts) }
            ?.let { n -> traces.firstOrNull { it.lap.number == n } }
        val a = trace(fastest)
        val b = trace(otherIndex)
        val source = LapVideoSource.of(VideoTimeline.from(data.events, indexed), dir, present)
        return if (a != null && b != null) LapVideoReview(VideoLap(a, source), VideoLap(b, source)) else null
    }

    /** The fastest lap against timed lap [otherIndex]; null without a fastest lap or an axis. */
    fun compare(otherIndex: Int): ReviewCompare? {
        val analysis = analysis ?: return null
        val fastest = analysis.laps.fastestLapIndex
        val comparison = fastest?.let { compareLaps(analysis, checkNotNull(session), it, otherIndex) }
        return comparison?.let {
            val laps = analysis.laps.timedLaps
            ReviewCompare(
                referenceNumber = laps[it.referenceIndex].number,
                otherNumber = laps[it.otherIndex].number,
                otherIndex = it.otherIndex,
                lengthMeters = it.lengthMeters,
                delta = it.delta,
                referenceSpeed = it.referenceSpeed,
                otherSpeed = it.otherSpeed,
                corners = cornerBands(analysis),
            )
        }
    }
}

/** The corners of [analysis], as Android shades them behind its charts. */
private fun cornerBands(analysis: SessionAnalysis) = analysis.segments?.segments.orEmpty()
    .filter { it.type == TrackSegmentType.Corner }
    .map { ReviewBand(it.name, it.startProgressMeters, it.endProgressMeters) }

/**
 * Reviews [data] (session [SessionData.id]) at the track it was driven on, among [tracks] (see
 * [trackFor]). [others] gives the other sessions at that track, read one at a time, whose passes
 * set the targets of where to find time, as on Android (LineSessions).
 */
fun reviewOf(
    data: SessionData,
    tracks: List<Track>,
    others: (Track) -> Sequence<LineSession> = { emptySequence() },
): SessionReview = reviewSession(data, tracks, others).review

/** As [reviewOf], keeping the analysis for [ReviewedSession.compare]. */
fun reviewSession(
    data: SessionData,
    tracks: List<Track>,
    others: (Track) -> Sequence<LineSession> = { emptySequence() },
): ReviewedSession {
    val track = trackFor(data, tracks)
        ?: return ReviewedSession(
            SessionReview(null, emptyList(), null, null), null, null,
            drive = driveMap(telemetrySessionOf(data, null)),
        )
    val session = telemetrySessionOf(data, track)
    val analysis = analyzeSession(session, identity = track.identity)
    val fastest = analysis.laps.fastestLapIndex
    val laps = analysis.laps.timedLaps.mapIndexed { i, lap ->
        ReviewLap(lap.number, lap.durationSeconds, lap.deltaToBestSeconds, i == fastest, lap.referenceEligible)
    }
    val origin = analysis.axis?.origin
    val plan = origin?.let { analyzeLine(analysis, session, others(track)) }?.let {
        val identity = analysis.identity?.number?.takeIf { analysis.identified }
        linePlan(it, origin, track.id, data.id, data.info.startUtcMillis, identity)
    }
    val line = plan?.let { lineUi(it, data.id) }
    val review = SessionReview(
        track,
        laps,
        fastest?.let { analysis.laps.timedLaps[it].durationSeconds },
        analysis.theoreticalBest?.totalSeconds,
        line,
    )
    // A session at the track with no complete lap is still seen on the map (FBBD-342).
    val drive = if (laps.isEmpty()) driveMap(session) else null
    return ReviewedSession(review, analysis, session, data, plan, drive)
}
