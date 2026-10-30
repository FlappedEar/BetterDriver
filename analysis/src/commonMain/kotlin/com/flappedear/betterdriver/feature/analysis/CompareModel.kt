package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.lapCoasting

/** The two laps [comparison] sets side by side, with [corners] shaded behind its charts. */
fun compareUi(analysis: SessionAnalysis, comparison: LapComparison, corners: List<CornerBand>): CompareUi {
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

/** What each state is called on the driving-state strips' key (FBBD-175), in the key's order. */
val DrivingStateNames = mapOf(
    DrivingState.Braking to "Brake",
    DrivingState.Coasting to "Coast",
    DrivingState.Cornering to "Corner",
    DrivingState.Accelerating to "Throttle",
    DrivingState.FullThrottle to "Flat out",
)

/** One state of the strips' key and its name, for an app that cannot read a map in order. */
data class DrivingStateKey(val state: DrivingState, val name: String)

/** The strips' key, in order. */
val DrivingStateKeys: List<DrivingStateKey> = DrivingStateNames.map { (state, name) -> DrivingStateKey(state, name) }

/** The strips' heading, marked when the states are not read from both pedals. */
fun drivingStatesLabel(ui: CompareUi): String = marked("What the car is doing", ui.statesProvenance)

/** "Coasting: lap 2 1.32 s · lap 3 2.05 s", marked when not measured; null when neither lap has it. */
fun coastingText(ui: CompareUi): String? {
    val laps = listOfNotNull(
        ui.referenceCoastingSeconds?.let { "lap ${ui.referenceNumber} ${marked(seconds(it), ui.statesProvenance)} s" },
        ui.otherCoastingSeconds?.let { "lap ${ui.otherNumber} ${marked(seconds(it), ui.statesProvenance)} s" },
    )
    return if (laps.isEmpty()) null else "Coasting: " + laps.joinToString(" · ")
}
