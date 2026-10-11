package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.LineConsensus
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.SegmentReview
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.computeSegmentReview
import com.flappedear.betterdriver.analysis.segments.computeTheoreticalBest
import com.flappedear.betterdriver.analysis.segments.lineConsensus
import com.flappedear.betterdriver.analysis.segments.timePopulation

/**
 * One session analysed end to end: its laps, the corners and straights proposed on its fastest
 * eligible lap, every lap's sector times on that lap's axis and the theoretical best of the
 * eligible laps.
 *
 * Telemetry stores approved segments per track configuration and adopts the best lap's proposals
 * only when no run has approved any; BetterDriver has no segment editor yet, so a session always
 * uses its own best lap's proposals (revision `auto-lap-N`).
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
)

/**
 * Analyses [session]: laps from its start gate, the axis of the fastest eligible lap with every lap
 * projected onto it, then segments, sector times and theoretical best. Segments are left out when
 * the best lap cannot be split (a circle has no straight) or its line disagrees with the other laps;
 * laps can still be compared by distance then.
 */
@Suppress("ReturnCount")
fun analyzeSession(
    session: TelemetrySession,
    laps: LapSession = deriveSourceLapSession(session),
): SessionAnalysis {
    val fastest = laps.fastestLapIndex?.let { laps.timedLaps[it] } ?: return SessionAnalysis(laps)
    val review = computeSegmentReview(
        session, laps, fastest.number, fastest.startTelemetryTime, fastest.endTelemetryTime,
    )
    val consensus = lineConsensus(review.axis, laps.lapTraces.filter { it.lapNumber != fastest.number })
    val segments = if (review.unavailable == null && !consensus.disagrees) {
        ApprovedSegments(
            review.proposals.proposals.mapIndexed { i, p ->
                TrackSegment("s$i", p.name, p.type, p.start.progressMeters, p.end.progressMeters)
            },
            revision = "auto-lap-${fastest.number}",
        )
    } else {
        null
    }
    val population = laps.timedLaps.map {
        PopulationLap(session, it.startTelemetryTime, it.endTelemetryTime, it.number)
    }
    val timed = timePopulation(laps, fastest.number, segments ?: NoSegments, population)
        ?: return SessionAnalysis(laps, fastest.number, review, consensus)
    val eligible = laps.timedLaps.map { it.referenceEligible }
    val best = segments?.let { computeTheoreticalBest(it, timed.times.filterIndexed { i, _ -> eligible[i] }) }
    return SessionAnalysis(
        laps, fastest.number, review, consensus, segments, timed.axis,
        if (segments == null) emptyList() else timed.times, timed.traces, best,
    )
}

private val NoSegments = ApprovedSegments(emptyList(), "")
