package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.recorder.Provenance

/**
 * Where the channels the analysis reads come from (FBBD-174), each null when the session has none
 * or does not say. The screens mark a value whose provenance is not measured; a value worked out
 * from the laps (a theoretical best, the time delta between two laps) is [Provenance.CALCULATED].
 */
data class AnalysisProvenance(
    val position: Provenance? = null,
    val speed: Provenance? = null,
    /** The longitudinal acceleration that braking is inferred from and peak braking read from. */
    val acceleration: Provenance? = null,
    val brake: Provenance? = null,
    val throttle: Provenance? = null,
) {
    /** Of a braking point found by [method] (a corner metric's provenance, "measured" or "inferred"). */
    fun braking(method: String): Provenance? = pedalPoint(method, brake)

    /** Of a throttle pickup found by [method], as [braking]. */
    fun pickup(method: String): Provenance? = pedalPoint(method, throttle)

    private fun pedalPoint(method: String, pedal: Provenance?): Provenance? = when (method) {
        BrakingProvenanceInferred -> Provenance.INFERRED
        BrakingProvenanceMeasured -> pedal
        else -> null
    }
}

/** The provenance of [session]'s channels the analysis reads, by their aliases. */
fun analysisProvenance(session: TelemetrySession) = AnalysisProvenance(
    position = session.provenanceOf(Aliases.LATITUDE),
    speed = session.provenanceOf(Aliases.SPEED),
    acceleration = session.provenanceOf(Aliases.LONGITUDINAL_ACCELERATION),
    brake = session.provenanceOf(Aliases.BRAKE),
    throttle = session.provenanceOf(Aliases.THROTTLE),
)
