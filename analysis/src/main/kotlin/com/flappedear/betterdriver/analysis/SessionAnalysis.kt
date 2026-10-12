package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.confidence.SessionConfidence
import com.flappedear.betterdriver.analysis.confidence.assessConfidence
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerVariability
import com.flappedear.betterdriver.analysis.corners.measureCornerLap
import com.flappedear.betterdriver.analysis.corners.summarizeCornerVariability
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.identity.identifySegments
import com.flappedear.betterdriver.analysis.identity.identityRevisionTag
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.LineConsensus
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.SegmentReview
import com.flappedear.betterdriver.analysis.segments.SegmentReviewSmoothingMeters
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TimeLossRanking
import com.flappedear.betterdriver.analysis.segments.TimedLapSectors
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.computeSegmentReview
import com.flappedear.betterdriver.analysis.segments.computeTheoreticalBest
import com.flappedear.betterdriver.analysis.segments.lineConsensus
import com.flappedear.betterdriver.analysis.segments.publishTheoreticalBest
import com.flappedear.betterdriver.analysis.segments.rankTimeLosses
import com.flappedear.betterdriver.analysis.segments.timePopulation
import com.flappedear.betterdriver.analysis.segments.wrapLengthMeters
import com.flappedear.betterdriver.timing.track.TrackRevision

/**
 * One session analysed end to end: its laps, the corners and straights proposed on its fastest
 * eligible lap, every lap's sector times on that lap's axis, the theoretical best of the eligible
 * laps, where each lap loses time against the fastest, and the Corner Analyzer's metrics of every
 * corner on every lap.
 *
 * Telemetry stores approved segments per track configuration and adopts the best lap's proposals
 * only when no run has approved any. BetterDriver keeps them with the track as its corner identity
 * (FBBD-172, [identity]): the corners and straights proposed on the first lap analysed there, placed
 * on each session's fastest lap by position, so segment ids and corner numbers stay the same
 * whichever lap is the fastest and however often a session is analysed.
 */
data class SessionAnalysis(
    val laps: LapSession,
    /** The fastest eligible lap's number, or null when no lap is eligible. */
    val axisLapNumber: Int? = null,
    val review: SegmentReview? = null,
    /** Whether the best lap's line agrees with the other eligible laps ([LineConsensus.disagrees]). */
    val consensus: LineConsensus = LineConsensus(),
    val segments: ApprovedSegments? = null,
    val axis: ProgressAxis? = null,
    /**
     * Sector times of every timed lap, in [LapSession.timedLaps] order (lap references are lap
     * numbers); empty without [segments].
     */
    val lapTimes: List<LapSectorTimes> = emptyList(),
    /** Every timed lap projected onto [axis], in the same order. */
    val traces: List<List<ProgressSegment>> = emptyList(),
    /** From eligible laps only. */
    val theoreticalBest: TheoreticalBestLap? = null,
    /**
     * The theoretical best as the screens show it: what the fastest lap loses in each segment,
     * largest first, and how repeatable each segment is over the eligible laps.
     */
    val summary: TheoreticalBestSummary? = null,
    /** Every eligible lap's losses against the fastest, window by window, largest first. */
    val losses: TimeLossRanking? = null,
    /**
     * Speeds, braking and exit of each corner (by segment id) on every timed lap, in
     * [LapSession.timedLaps] order, measured on [axis].
     */
    val cornerMetrics: Map<String, List<CornerLapMetrics>> = emptyMap(),
    /** How repeatable each corner's braking, speeds, pickup and line are over the eligible laps. */
    val variability: Map<String, CornerVariability> = emptyMap(),
    /** Where the channels the analysis reads come from (FBBD-174). */
    val provenance: AnalysisProvenance = AnalysisProvenance(),
    /**
     * The track's corner identity [segments] come from (FBBD-172): the one passed in, or a new one,
     * the first for the track or the next revision when the fastest lap left the old one's shape.
     * Kept with the track when it differs from the one passed in. Null without segments.
     */
    val identity: TrackRevision? = null,
    /**
     * How far its conclusions can be trusted (FBBD-177): what the position evidence shows in each
     * segment on each lap, and what applies to the whole session.
     */
    val confidence: SessionConfidence = SessionConfidence(),
) {
    /** Whether [segments] are [identity]'s corners and straights, under its ids (FBBD-172). */
    val identified: Boolean
        get() = identity != null && segments?.revision == identityRevisionTag(identity.number)
}

/**
 * Analyses [session]: laps from its start gate, the axis of the fastest eligible lap with every lap
 * projected onto it, then segments, sector times and theoretical best. Segments are the track's
 * [identity] (FBBD-172) placed on that axis, or proposed on it when there is none yet or the lap no
 * longer drives its shape ([SessionAnalysis.identity]). They are left out when the best lap's line
 * disagrees with the other laps, or when there is no identity to place and the best lap cannot be
 * split (a circle has no straight); laps can still be compared by distance then.
 */
fun analyzeSession(
    session: TelemetrySession,
    laps: LapSession = deriveSourceLapSession(session),
    identity: TrackRevision? = null,
): SessionAnalysis {
    val analysis = analyzeLaps(session, laps, identity).copy(provenance = analysisProvenance(session))
    return analysis.copy(confidence = assessConfidence(session, analysis))
}

@Suppress("ReturnCount", "LongMethod")
private fun analyzeLaps(
    session: TelemetrySession,
    laps: LapSession,
    identity: TrackRevision?,
): SessionAnalysis {
    val fastestIndex = laps.fastestLapIndex ?: return SessionAnalysis(laps)
    val fastest = laps.timedLaps[fastestIndex]
    val review = computeSegmentReview(
        session, laps, fastest.number, fastest.startTelemetryTime, fastest.endTelemetryTime,
    )
    val consensus = lineConsensus(review.axis, laps.lapTraces.filter { it.lapNumber != fastest.number })
    val identified = if (consensus.disagrees || !review.axis.valid) {
        null
    } else {
        val proposals = if (review.unavailable == null) review.proposals.proposals else emptyList()
        identifySegments(review.axis, proposals, identity)
    }
    val segments = identified?.segments
    val population = laps.timedLaps.map {
        PopulationLap(session, it.startTelemetryTime, it.endTelemetryTime, it.number)
    }
    val timed = timePopulation(laps, fastest.number, segments ?: NoSegments, population)
        ?: return SessionAnalysis(laps, fastest.number, review, consensus)
    if (segments == null) {
        return SessionAnalysis(laps, fastest.number, review, consensus, axis = timed.axis, traces = timed.traces)
    }
    val eligible = laps.timedLaps.indices.filter { laps.timedLaps[it].referenceEligible }
    val eligibleTimes = eligible.map { timed.times[it] }
    val best = computeTheoreticalBest(segments, eligibleTimes)
    val fastestTimes = timed.times[fastestIndex]
    val losses = rankTimeLosses(
        segments,
        wrapLengthMeters(timed.axis),
        eligible.map { TimedLapSectors(timed.times[it], population[it].start) },
        TimedLapSectors(fastestTimes, fastest.startTelemetryTime),
        maximumResults = -1, // all: focus areas count every lap's losses through a segment
    )
    val features = computeTrackFeatures(timed.axis, SegmentReviewSmoothingMeters)
    val cornerMetrics = segments.segments.filter { it.type == TrackSegmentType.Corner }.associate { corner ->
        corner.id to laps.timedLaps.mapIndexed { i, lap ->
            measureCornerLap(
                timed.axis, features, segments, corner.id, corner.startProgressMeters, corner.endProgressMeters,
                timed.traces[i], session, lap.startTelemetryTime, lap.endTelemetryTime, lap.number,
            )
        }
    }
    val variability = segments.segments.filter { it.id in cornerMetrics }.associate { corner ->
        val observations = eligible.map { cornerMetrics.getValue(corner.id)[it].observation }
        corner.id to summarizeCornerVariability(corner.id, corner.name, observations)
    }
    return SessionAnalysis(
        laps, fastest.number, review, consensus, segments, timed.axis, timed.times, timed.traces, best,
        publishTheoreticalBest(segments, best, eligibleTimes, fastestTimes), losses, cornerMetrics, variability,
        identity = identified?.identity,
    )
}

private val NoSegments = ApprovedSegments(emptyList(), "")
