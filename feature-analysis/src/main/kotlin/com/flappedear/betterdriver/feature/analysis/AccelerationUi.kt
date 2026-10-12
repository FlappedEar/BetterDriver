package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.runs.AccelerationRun
import com.flappedear.betterdriver.analysis.runs.AccelerationRunKind
import com.flappedear.betterdriver.analysis.runs.RunQualityIssue

/** One acceleration run as the Acceleration card lists it. */
data class RunRow(
    val seconds: Double,
    /** Session time the run started, seconds from the first sample. */
    val atSeconds: Double,
    /** Speed at the finish of a distance run, km/h; null for a speed run. */
    val trapKmh: Double?,
    val peakG: Double?,
    /** Why the time is less certain, or empty. */
    val note: String,
)

/** The runs of one kind, quickest first: [best] and up to [MaxRunsPerKind] - 1 [others]. */
data class RunKindRow(val label: String, val best: RunRow, val others: List<RunRow>, val count: Int)

/** The Acceleration card (FBBD-154): the runs found in the session, by kind. */
data class AccelerationUi(val kinds: List<RunKindRow>)

/** At most this many runs of one kind are listed. */
const val MaxRunsPerKind = 10

private fun label(kind: AccelerationRunKind) = when (kind) {
    AccelerationRunKind.ZeroTo100Kmh -> "0–100 km/h"
    AccelerationRunKind.From100To200Kmh -> "100–200 km/h"
    AccelerationRunKind.ZeroTo60Mph -> "0–60 mph"
    AccelerationRunKind.EighthMile -> "1/8 mile"
    AccelerationRunKind.QuarterMile -> "1/4 mile"
}

private fun note(issues: Set<RunQualityIssue>) = issues.sortedBy { it.ordinal }.joinToString(", ") {
    when (it) {
        RunQualityIssue.LowRate -> "GPS under 10 Hz"
        RunQualityIssue.PoorAccuracy -> "GPS accuracy over 2 m"
        RunQualityIssue.Gap -> "GPS gap"
    }
}

/**
 * The card's model of [runs] (as `findAccelerationRuns` returns them: kinds in order, best first
 * within each), or null when there is none, so the card is not shown.
 */
fun accelerationUi(runs: List<AccelerationRun>): AccelerationUi? {
    if (runs.isEmpty()) return null
    val kinds = runs.groupBy { it.kind }.map { (kind, ofKind) ->
        val rows = ofKind.take(MaxRunsPerKind).map {
            RunRow(it.timeSeconds, it.startSeconds, it.trapSpeedKmh, it.peakG, note(it.quality))
        }
        RunKindRow(label(kind), rows.first(), rows.drop(1), ofKind.size)
    }
    return AccelerationUi(kinds)
}
