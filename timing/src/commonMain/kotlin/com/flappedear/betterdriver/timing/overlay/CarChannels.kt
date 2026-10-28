package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.ChannelDef

/**
 * The car's and the driver's own values the video overlay shows (FBBD-347), each found by its name
 * as the app records it ("obd_rpm", the FR-S's "obd_steering_angle", "heart_rate") or as RaceChrono
 * exports it ("rpm-obd", "engine_oil_temp-obd", "gearbox_temp-obd"), with the names the lap
 * analysis and the coach use for the same values.
 */
enum class CarValue(pattern: String) {
    Rpm("^(?:obd_)?(?:engine_)?rpm(?:-obd|-canbus)?$"),

    /** The accelerator pedal in %, preferred over the throttle plate as the driver's input. */
    Accelerator("^(?:obd_)?accelerator(?:.?(?:pedal|pos))?(?:-obd|-canbus)?$|^(?:obd_)?pedal_[de]$"),

    /** The throttle plate in %: the pedal when the car gives no accelerator pedal. */
    Throttle("^(?:obd_)?throttle(?:.?pos(?:ition)?)?(?:-obd|-canbus)?$"),

    /** The brake pedal in % (the FR-S's CAN and the Corolla's PID), not the line pressure in bar. */
    Brake("^(?:obd_)?brake(?:.?pos(?:ition)?)?(?:-obd|-canbus)?$"),

    /** The steering wheel in degrees, positive left (FBBD-263). */
    SteeringAngle("^(?:obd_)?steering(?:_wheel)?_angle(?:-obd|-canbus)?$"),
    HeartRate("^heart_?rate$"),
    OilTemp("^(?:obd_)?(?:engine_)?oil_temp(?:-obd|-canbus)?$"),
    GearboxTemp("^(?:obd_)?(?:atf|gearbox|transmission)_temp(?:-obd|-canbus)?$"),
    CoolantTemp("^(?:obd_)?(?:engine_)?coolant_temp(?:-obd|-canbus)?$"),

    /** Tyre temperatures in °C and pressures in bar, front left to rear right (2.0 names). */
    TyreTempFl("^tyre_temp_fl$"),
    TyreTempFr("^tyre_temp_fr$"),
    TyreTempRl("^tyre_temp_rl$"),
    TyreTempRr("^tyre_temp_rr$"),
    TyrePressureFl("^tyre_pressure_fl$"),
    TyrePressureFr("^tyre_pressure_fr$"),
    TyrePressureRl("^tyre_pressure_rl$"),
    TyrePressureRr("^tyre_pressure_rr$"),
    ;

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    fun matches(name: String): Boolean = regex.matches(name)

    companion object {
        val TyreTemps = listOf(TyreTempFl, TyreTempFr, TyreTempRl, TyreTempRr)
        val TyrePressures = listOf(TyrePressureFl, TyrePressureFr, TyrePressureRl, TyrePressureRr)
    }
}

/**
 * The channel id of each value [channels] record, the first by name when several match. Which of
 * them have samples is up to the session: a value without any is left out by [OverlayData].
 */
internal fun carChannelIds(channels: Collection<ChannelDef>): Map<CarValue, Int> {
    val sorted = channels.sortedBy { it.name.lowercase() }
    return CarValue.entries.mapNotNull { v -> sorted.firstOrNull { v.matches(it.name) }?.let { v to it.id } }.toMap()
}

/**
 * The car's values at one moment of the session; null where the session has none then. [tyreTemps]
 * and [tyrePressures] are front left, front right, rear left, rear right.
 */
data class CarFrame(
    val rpm: Double? = null,
    /** The accelerator pedal, else the throttle plate, in %. */
    val throttlePct: Double? = null,
    val brakePct: Double? = null,
    val steeringDeg: Double? = null,
    val heartRate: Double? = null,
    val oilC: Double? = null,
    val gearboxC: Double? = null,
    val coolantC: Double? = null,
    val tyreTemps: List<Double?> = NoTyres,
    val tyrePressures: List<Double?> = NoTyres,
)

private val NoTyres = listOf<Double?>(null, null, null, null)
