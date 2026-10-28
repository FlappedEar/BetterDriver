package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.ChannelDef

/**
 * The car's and the driver's own values the video overlay shows (FBBD-347), each found by its name
 * as the app records it ("obd_rpm", the FR-S's "obd_steering_angle", "heart_rate") or as RaceChrono
 * exports it ("rpm-obd", "engine_oil_temp-obd", "gearbox_temp-obd"), with the names the lap
 * analysis and the coach use for the same values.
 *
 * [maxGapMicros] is the longest wait between two readings that still counts as one stretch of
 * values: the recorder polls temperatures far more slowly than the engine and pedals (0.5 Hz, 0.2 Hz
 * while it listens to the FR-S's CAN broadcasts), so a temperature is held across its own interval.
 */
enum class CarValue(pattern: String, val maxGapMicros: Long = FastGapMicros) {
    Rpm("^(?:obd_)?(?:engine_)?rpm(?:-obd|-canbus)?$"),

    /** The accelerator pedal in %, preferred over the throttle plate as the driver's input. */
    Accelerator("^(?:obd_)?accelerator(?:.?(?:pedal|pos))?(?:-obd|-canbus)?$|^(?:obd_)?pedal_[de]$"),

    /** The throttle plate in %: the pedal when the car gives no accelerator pedal. */
    Throttle("^(?:obd_)?throttle(?:.?pos(?:ition)?)?(?:-obd|-canbus)?$"),

    /** The brake pedal in % (the FR-S's CAN and the Corolla's PID), not the line pressure in bar. */
    Brake("^(?:obd_)?brake(?:.?pos(?:ition)?)?(?:-obd|-canbus)?$"),

    /** The steering wheel in degrees, positive left (FBBD-263). */
    SteeringAngle("^(?:obd_)?steering(?:_wheel)?_angle(?:-obd|-canbus)?$"),
    HeartRate("^heart_?rate$", HeartRateGapMicros),
    OilTemp("^(?:obd_)?(?:engine_)?oil_temp(?:-obd|-canbus)?$", SlowGapMicros),
    GearboxTemp("^(?:obd_)?(?:atf|gearbox|transmission)_temp(?:-obd|-canbus)?$", SlowGapMicros),
    CoolantTemp("^(?:obd_)?(?:engine_)?coolant_temp(?:-obd|-canbus)?$", SlowGapMicros),

    /** Tyre temperatures in °C and pressures in bar, front left to rear right (2.0 names). */
    TyreTempFl("^tyre_temp_fl$", SlowGapMicros),
    TyreTempFr("^tyre_temp_fr$", SlowGapMicros),
    TyreTempRl("^tyre_temp_rl$", SlowGapMicros),
    TyreTempRr("^tyre_temp_rr$", SlowGapMicros),
    TyrePressureFl("^tyre_pressure_fl$", SlowGapMicros),
    TyrePressureFr("^tyre_pressure_fr$", SlowGapMicros),
    TyrePressureRl("^tyre_pressure_rl$", SlowGapMicros),
    TyrePressureRr("^tyre_pressure_rr$", SlowGapMicros),
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

/** Engine, pedals and wheel come at 7 Hz or more: a reading 2 s late is a gap, as for the GNSS. */
private const val FastGapMicros = TimeSeries.DefaultMaxGapMicros

/** A heart-rate strap sends about once a second. */
private const val HeartRateGapMicros = 5_000_000L

/** Three intervals of the slowest temperature polling (0.2 Hz). */
private const val SlowGapMicros = 15_000_000L

private val NoTyres = listOf<Double?>(null, null, null, null)
