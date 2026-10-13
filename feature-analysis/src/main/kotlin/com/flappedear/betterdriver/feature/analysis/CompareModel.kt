package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.driving.lapCoasting

/** The two laps [comparison] sets side by side, with [corners] shaded behind its charts. */
internal fun compareUi(analysis: SessionAnalysis, comparison: LapComparison, corners: List<CornerBand>): CompareUi {
    val laps = analysis.laps.timedLaps
    val reference = comparison.referenceIndex
    val other = comparison.otherIndex
    return CompareUi(
        referenceNumber = laps[reference].number,
        otherNumber = laps[other].number,
        lengthMeters = comparison.lengthMeters,
        delta = comparison.delta,
        referenceSpeed = comparison.referenceSpeed,
        otherSpeed = comparison.otherSpeed,
        corners = corners,
        speedProvenance = analysis.provenance.speed,
        referenceStates = analysis.drivingStates.getOrNull(reference)?.runs.orEmpty(),
        otherStates = analysis.drivingStates.getOrNull(other)?.runs.orEmpty(),
        referenceCoastingSeconds = analysis.lapCoasting(reference)?.seconds,
        otherCoastingSeconds = analysis.lapCoasting(other)?.seconds,
        statesProvenance = coastingProvenance(
            analysis.lapCoasting(reference) ?: analysis.lapCoasting(other),
            analysis.provenance,
        ),
    )
}
