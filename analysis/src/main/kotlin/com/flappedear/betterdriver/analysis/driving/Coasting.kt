package com.flappedear.betterdriver.analysis.driving

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency

// Coasting analysis (FBBD-175), after FlappedEar Telemetry's: the time and distance a lap spends
// off both pedals in each corner (by its CornerId, the segment id the corner identity gives it,
// FBBD-172) and over the whole lap, from the lap's driving states. A corner is its segment, from
// its entry to its exit; braking before the entry is not part of it.
//
// Coaching question it answers: in which corner does the car roll between the brake and the
// throttle, and how much — and is that the same lap after lap?

/** A range must have a driving state over at least this share of it for a coasting time. */
const val MinimumCoastingCoverage = 0.9

/**
 * Coasting over one corner or lap: [seconds] and [meters] off both pedals, found as [provenance]
 * says (`measured` from the brake and throttle channels, `inferred` from the acceleration, a lower
 * bound then).
 */
data class Coasting(val seconds: Double, val meters: Double, val provenance: String)

/** Coasting in [totals], or null when too little of the range has a state. */
private fun coastingOf(totals: DrivingStateTotals, provenance: String): Coasting? =
    if (totals.coverage >= MinimumCoastingCoverage) {
        Coasting(totals.seconds(DrivingState.Coasting), totals.meters(DrivingState.Coasting), provenance)
    } else {
        null
    }

/** Coasting of timed lap [lapIndex] over the whole lap; null without its driving states. */
fun SessionAnalysis.lapCoasting(lapIndex: Int): Coasting? {
    val states = drivingStates.getOrNull(lapIndex)?.takeIf { it.valid } ?: return null
    return coastingOf(states.totals(), states.provenance)
}

/** Coasting of timed lap [lapIndex] through corner [segmentId]; null without either. */
fun SessionAnalysis.cornerCoasting(segmentId: String, lapIndex: Int): Coasting? {
    val segment = segments?.segments?.firstOrNull { it.id == segmentId }
    val states = drivingStates.getOrNull(lapIndex)?.takeIf { it.valid }
    if (segment == null || states == null) return null
    return coastingOf(states.totals(segment.startProgressMeters, segment.endProgressMeters), states.provenance)
}

/** How repeatable the coasting time through [segmentId] is over the counted laps (median and interquartile range). */
fun SessionAnalysis.cornerCoastingConsistency(segmentId: String): ConsistencySummary =
    summarizeConsistency(
        laps.timedLaps.indices.filter { laps.timedLaps[it].referenceEligible }
            .mapNotNull { cornerCoasting(segmentId, it)?.seconds },
    )
