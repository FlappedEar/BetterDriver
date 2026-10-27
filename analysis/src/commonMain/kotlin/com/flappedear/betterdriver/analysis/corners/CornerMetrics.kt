@file:Suppress(
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "MatchingDeclarationName",
) // Ported as written.

package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments

// Port of measureCornerLap of FlappedEar Telemetry analysis/outing_theoretical_best.dart
// (Apache 2.0): the Corner Analyzer's metrics of one corner on one lap, measured on the shared
// axis, and the observation the corner's variability is summarized from.

/** One corner on one lap: speeds, braking, exit and the observation summarized from them. */
data class CornerLapMetrics(
    val lapReference: Any?,
    val speeds: CornerSpeeds,
    val braking: BrakingMetrics,
    val exit: ExitMetrics,
    val observation: CornerLapObservation,
)

/** A boundary further than this off the axis is no data. */
private const val GateToleranceMeters = 1.0

/**
 * The metrics of corner [segmentId] (from [start] to [end]) on one lap projected as [trace]
 * onto [axis], timed from [lapStart] to [lapEnd].
 */
fun measureCornerLap(
    axis: ProgressAxis,
    features: TrackFeatures,
    approved: ApprovedSegments,
    segmentId: String,
    start: Double,
    end: Double,
    trace: List<ProgressSegment>,
    session: TelemetrySession,
    lapStart: Double,
    lapEnd: Double,
    lapReference: Any?,
): CornerLapMetrics {
    var observation = CornerLapObservation(lapReference)
    val speeds = computeCornerSpeeds(axis, features, approved, segmentId, trace, session)
    if (speeds.valid) {
        observation = observation.copy(
            apexSpeed = speeds.apex.value,
            minimumSpeed = speeds.minimum.value,
            exitSpeed = speeds.exit.value,
            speedUnit = speeds.unit,
        )
    }
    val braking = computeBrakingMetrics(axis.lengthMeters, approved, segmentId, trace, session, lapStart, lapEnd)
    if (braking.valid && braking.brakingPointMeters != null) {
        observation = observation.copy(
            brakingPointMeters = braking.brakingPointMeters,
            brakingProvenance = braking.provenance,
        )
    }
    val exit = computeExitMetrics(axis.lengthMeters, approved, segmentId, trace, session, lapEnd)
    if (exit.valid && exit.pickup.progressMeters != null) {
        observation = observation.copy(
            pickupMeters = exit.pickup.progressMeters,
            pickupProvenance = exit.pickup.provenance,
        )
    }
    // Line at the geometric apex when one exists, else mid-corner (a chain has several apexes).
    val middle = if (end >= start) {
        (start + end) / 2.0
    } else {
        (start + (end + axis.lengthMeters - start) / 2.0) % axis.lengthMeters
    }
    val at = if (speeds.valid && speeds.apex.value != null) speeds.apex.progressMeters else middle
    val time = timeAtProgress(trace, at)
    if (time != null) {
        val latitude = session.valueAt(Aliases.LATITUDE, time)
        val longitude = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude)
        if (latitude != null && longitude != null) {
            observation = observation.copy(
                lineOffsetMeters = lateralOffsetMeters(
                    axis, at, projectCoordinate(GeoCoordinate(latitude, longitude), axis.origin),
                ),
            )
        }
        observation = observation.copy(gpsAccuracyMeters = session.valueAt(Aliases.ACCURACY, time))
    }

    // And where the corner starts and ends. A boundary on the gate is measured where the lap starts
    // or ends: a lap's trace begins after the gate, so no time has progress 0.
    fun offsetAt(progress: Double): Double? {
        val outside = progress < -GateToleranceMeters || progress > axis.lengthMeters + GateToleranceMeters
        if (!progress.isFinite() || outside) {
            return null
        }
        val at2 = when {
            progress <= 0.0 -> lapStart
            progress >= axis.lengthMeters -> lapEnd
            else -> timeAtProgress(trace, progress)
        } ?: return null
        val latitude = session.valueAt(Aliases.LATITUDE, at2) ?: return null
        val longitude = session.valueAt(Aliases.LONGITUDE, at2) ?: return null
        return lateralOffsetMeters(
            axis,
            progress.coerceIn(0.0, axis.lengthMeters),
            projectCoordinate(GeoCoordinate(latitude, longitude), axis.origin),
        )
    }
    observation = observation.copy(entryLineOffsetMeters = offsetAt(start), exitLineOffsetMeters = offsetAt(end))
    return CornerLapMetrics(lapReference, speeds, braking, exit, observation)
}
