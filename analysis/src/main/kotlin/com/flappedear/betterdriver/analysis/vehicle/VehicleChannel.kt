package com.flappedear.betterdriver.analysis.vehicle

import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession

/**
 * The car's own values that the analysis after a session reads (FBBD-176), each found by its name as
 * the app records it over OBD ("obd_rpm", "obd_oil_temp", the FR-S's "obd_atf_temp") or as RaceChrono
 * exports it ("rpm-obd", "engine_oil_temp-obd", "gearbox_temp-obd"). Only measured values count: a
 * channel worked out or of unknown origin is not the car's.
 */
enum class VehicleChannel(pattern: String) {
    Rpm("^(?:obd_)?(?:engine_)?rpm(?:-obd|-canbus)?$"),
    OilTemp("^(?:obd_)?(?:engine_)?oil_temp(?:-obd|-canbus)?$"),
    CoolantTemp("^(?:obd_)?(?:engine_)?coolant_temp(?:-obd|-canbus)?$"),
    GearboxTemp("^(?:obd_)?(?:atf|gearbox|transmission)_temp(?:-obd|-canbus)?$"),
    IntakeTemp("^(?:obd_)?intake(?:_air)?_temp(?:-obd|-canbus)?$"),

    /** The steering wheel in degrees, positive left: the gen-1 FR-S's CAN broadcast (FBBD-263, FBBD-321). */
    SteeringAngle("^(?:obd_)?steering(?:_wheel)?_angle(?:-obd|-canbus)?$"),

    /** The brake line pressure in bar: the gen-1 FR-S's CAN broadcast (FBBD-320, FBBD-322). */
    BrakePressure("^(?:obd_)?brake_pressure(?:-obd|-canbus)?$"),
    ;

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Whether a channel called [name] is this value. */
    fun matches(name: String): Boolean = regex.matches(name)

    companion object {
        /** The temperatures, in the order a session's temperature card lists them. */
        val Temperatures = listOf(OilTemp, CoolantTemp, GearboxTemp, IntakeTemp)
    }
}

/**
 * The channel of [session] that records [kind]: a measured one with at least one value, the one with
 * the most values when there are several (first by name on a tie); null when the session has none.
 */
fun vehicleChannel(session: TelemetrySession, kind: VehicleChannel): TelemetryChannel? =
    session.channels.values
        .filter { kind.matches(it.name) && it.provenance?.measured == true }
        .map { it to it.values.count(Double::isFinite) }
        .filter { (_, finite) -> finite > 0 }
        .sortedWith(compareByDescending<Pair<TelemetryChannel, Int>> { it.second }.thenBy { it.first.name })
        .firstOrNull()
        ?.first
