package com.flappedear.betterdriver.analysis.laps

import com.flappedear.betterdriver.analysis.data.TimingGate

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/laps/lap_session.dart and
// lap_ranking.dart (Apache 2.0).

/** Whether laps could be derived from a recording, and why not. */
enum class LapSessionStatus {
    Available,
    NoStartGate,
    AmbiguousStartGate,
    InvalidGate,
    NoUsableGps,
    NoAcceptedPasses,
    InsufficientPasses,
}

/** Thresholds for detecting start-gate passes; the defaults are Telemetry's and Overlays'. */
data class LapDetectionOptions(
    /** A pass starts when the car's path comes this close to the gate. */
    val innerCorridorMeters: Double = 5.0,
    /** A pass ends when the path leaves this distance. */
    val outerCorridorMeters: Double = 10.0,
    val minimumGroundSpeedMetersPerSecond: Double = 2.0,
    /** Speed across the gate line. */
    val minimumNormalSpeedMetersPerSecond: Double = 2.0,
    /** Share of the ground speed that must be across the gate line. */
    val minimumNormalMotionRatio: Double = 0.10,
    /** Time after an accepted pass before the next one can start. */
    val refractorySeconds: Double = 1.0,
    val maximumClusterSeconds: Double = 5.0,
    val minimumGateLengthMeters: Double = 1.0,
    val maximumGateLengthMeters: Double = 200.0,
    val minimumLapSeconds: Double = 3.0,
    /** Longer than this is a stop, not a lap. */
    val maximumLapSeconds: Double = 3600.0,
    /** A lap whose GPS path is shorter than this is not plausible. */
    val minimumLapDistanceMeters: Double = 200.0,
    /**
     * Point-to-point runs (FBBD-155) in place of [minimumLapSeconds] and [minimumLapDistanceMeters]:
     * start and finish are different lines, so a short sprint is still a run.
     */
    val minimumRunSeconds: Double = 1.0,
    val minimumRunDistanceMeters: Double = 20.0,
    /** 100 m/s is 360 km/h on average. */
    val maximumAverageSpeedMetersPerSecond: Double = 100.0,
    /** A lap shorter than this share of the session's median lap path cut the circuit short. */
    val minimumLapDistanceRatio: Double = 0.8,
)

/** One accepted crossing of the start gate. */
data class GatePass(
    val telemetryTime: Double,
    val closestDistanceMeters: Double,
    /** +1 or -1: which side of the gate line the car crossed from. */
    val direction: Int,
    /** Where along the gate, 0 at endpoint A and 1 at endpoint B. */
    val gateFraction: Double,
    val groundSpeedMetersPerSecond: Double,
    val normalSpeedMetersPerSecond: Double,
)

/** Why a measured lap cannot be ranked or used as a spatial reference. */
enum class LapReferenceIssue {
    None,
    GpsGap,
    InvalidGps,

    /** Its time, path length or average speed cannot be a lap of the circuit. */
    ImplausibleLap,
}

/** A gate-to-gate interval. Times are seconds on the recording's clock. */
data class TimedLap(
    val number: Int,
    val startTelemetryTime: Double,
    val endTelemetryTime: Double,
    val durationSeconds: Double,
    /** Duration minus the fastest eligible lap's; 0 for an ineligible lap. */
    val deltaToBestSeconds: Double = 0.0,
    val referenceIssue: LapReferenceIssue = LapReferenceIssue.None,
    val userExclusionReason: String = "",
    /** Length of the lap's GPS path; null when its GPS is not complete. */
    val distanceMeters: Double? = null,
) {
    /** Whether the lap takes part in ranking, statistics and references. */
    val referenceEligible: Boolean
        get() = referenceIssue == LapReferenceIssue.None && userExclusionReason.isEmpty()
}

/** A position on a lap, in metres from the start gate's centre. */
data class LapTracePoint(val telemetryTime: Double, val eastMeters: Double, val northMeters: Double)

/** The path of one eligible lap, thinned to at most 4,096 points. */
data class LapTrace(
    val lapNumber: Int,
    val startTelemetryTime: Double,
    val durationSeconds: Double,
    val points: List<LapTracePoint>,
)

/** The laps of one recording. */
data class LapSession(
    val status: LapSessionStatus,
    val startGate: TimingGate? = null,
    val acceptedPasses: List<GatePass> = emptyList(),
    val timedLaps: List<TimedLap> = emptyList(),
    val lapTraces: List<LapTrace> = emptyList(),
    /** Index into [timedLaps] of the fastest eligible lap. */
    val fastestLapIndex: Int? = null,
    /** A point-to-point track's finish (FBBD-155): the laps are runs, and their axis is open (FBBD-162). */
    val finishGate: TimingGate? = null,
)

/** Finds the fastest eligible lap (the first on a tie) and sets every eligible lap's delta to it. */
fun rankLaps(laps: List<TimedLap>): Pair<List<TimedLap>, Int?> {
    var fastest: Int? = null
    laps.forEachIndexed { i, lap ->
        if (lap.referenceEligible && (fastest == null || lap.durationSeconds < laps[fastest!!].durationSeconds)) {
            fastest = i
        }
    }
    val f = fastest ?: return laps.map { it.copy(deltaToBestSeconds = 0.0) } to null
    val best = laps[f].durationSeconds
    return laps.map {
        it.copy(deltaToBestSeconds = if (it.referenceEligible) it.durationSeconds - best else 0.0)
    } to f
}
