package com.flappedear.betterdriver.analysis.summary

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.analysis.segments.timePopulation

// The segment part of FlappedEar Telemetry day/session_summary.dart (Apache 2.0): each corner and
// straight's typical time in this session against the session before. Telemetry times every run's
// laps on the day's shared axis; here the session before's laps are timed on this session's axis
// and segments, so both are measured through the same stretch of track.

/** A change in a segment's typical time smaller than this is within what a few laps vary by. */
const val SessionSummaryChangeSeconds = 0.05

/** The session before, read in full so its laps can be timed through this session's segments. */
data class PreviousLaps(val session: TelemetrySession, val laps: LapSession)

/** One segment's typical time in this session against the session before. */
data class SegmentChange(
    val segmentId: String,
    val name: String,
    val type: TrackSegmentType,
    /** This session's median time through the segment, over its counted laps. */
    val seconds: Double,
    /** The session before's. */
    val referenceSeconds: Double,
    /** Interquartile ranges of the two sessions' times through the segment. */
    val spreadSeconds: Double?,
    val referenceSpreadSeconds: Double?,
) {
    /** Negative is quicker. */
    val deltaSeconds: Double get() = seconds - referenceSeconds
}

/** [changes] quicker by [SessionSummaryChangeSeconds] or more, most first. */
fun quicker(changes: List<SegmentChange>) =
    changes.filter { it.deltaSeconds <= -SessionSummaryChangeSeconds }.sortedBy { it.deltaSeconds }

/** [changes] slower by [SessionSummaryChangeSeconds] or more, most first. */
fun slower(changes: List<SegmentChange>) =
    changes.filter { it.deltaSeconds >= SessionSummaryChangeSeconds }.sortedByDescending { it.deltaSeconds }

private fun ConsistencySummary.medianOrNull() = median.takeIf { available }

/**
 * Every segment of [analysis] with a typical time in both sessions, in track order. Typical times
 * need [com.flappedear.betterdriver.analysis.segments.MinimumConsistencySamples] counted laps
 * timed through the segment in each session. Empty without segments.
 */
@Suppress("ReturnCount")
fun segmentChanges(analysis: SessionAnalysis, previous: PreviousLaps): List<SegmentChange> {
    val segments = analysis.segments ?: return emptyList()
    val axisLap = analysis.axisLapNumber ?: return emptyList()
    val population = previous.laps.timedLaps.filter { it.referenceEligible }.map {
        PopulationLap(previous.session, it.startTelemetryTime, it.endTelemetryTime, it.number)
    }
    val before = timePopulation(analysis.laps, axisLap, segments, population)?.times ?: return emptyList()
    val now = analysis.laps.timedLaps.indices
        .filter { analysis.laps.timedLaps[it].referenceEligible }
        .mapNotNull { analysis.lapTimes.getOrNull(it) }
    return segments.segments.mapNotNull { segment ->
        val current = summarizeConsistency(now.mapNotNull { it.sector(segment.id)?.seconds })
        val earlier = summarizeConsistency(before.mapNotNull { it.sector(segment.id)?.seconds })
        val seconds = current.medianOrNull()
        val reference = earlier.medianOrNull()
        if (seconds == null || reference == null) {
            null
        } else {
            SegmentChange(
                segment.id, segment.name, segment.type, seconds, reference,
                current.interquartileRange, earlier.interquartileRange,
            )
        }
    }
}
