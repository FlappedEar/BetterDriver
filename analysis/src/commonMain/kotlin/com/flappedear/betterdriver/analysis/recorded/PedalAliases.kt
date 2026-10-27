package com.flappedear.betterdriver.analysis.recorded

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel

/** A throttle channel: the app's OBD "obd_throttle", RaceChrono's "throttle_pos-obd". */
private val ThrottlePattern = Regex("throttle|accelerator.?pedal", RegexOption.IGNORE_CASE)

/** The accelerator pedal itself, preferred over the throttle plate as the driver's input. */
private val AcceleratorPattern =
    Regex("^(?:obd_)?accelerator(?:.?(?:pedal|pos))?|^(?:obd_)?pedal_[de]$", RegexOption.IGNORE_CASE)

/** A brake channel: the app's OBD "obd_brake" (FR-S profile), RaceChrono's "brake_pos-obd". */
private val BrakePattern = Regex("^(?:obd_)?brake(?:[-_].*)?$|brake.?pressure|brake.?pedal", RegexOption.IGNORE_CASE)

/**
 * A pedal sampled less often than this (median interval) says too little about when it was pressed:
 * the braking point and throttle pickup are then inferred from the G, as without one. RaceChrono's
 * OBD columns run at 10 Hz; the app's own Wi-Fi reader at 0.4 Hz (FBBD-270) does not qualify.
 */
private const val MaxPedalIntervalSeconds = 0.5

/**
 * The [Aliases.THROTTLE] and [Aliases.BRAKE] inputs of a recording (FBBD-203), as FlappedEar Telemetry
 * maps them: measured channels only (an OBD or CAN pedal, never a value worked out or of unknown
 * origin), the accelerator pedal before the throttle plate when it covers as much of the session.
 */
internal fun pedalAliases(channels: Collection<TelemetryChannel>): Map<String, String> {
    val usable = channels
        .filter { c ->
            c.provenance?.measured == true && c.values.count { it.isFinite() } >= MinPedalSamples &&
                c.baseIntervalSeconds > 0.0 && c.baseIntervalSeconds <= MaxPedalIntervalSeconds
        }
        .sortedBy { it.name.lowercase() }
    fun isPedal(c: TelemetryChannel) = AcceleratorPattern.containsMatchIn(c.name)
    val throttle = usable.firstOrNull { ThrottlePattern.containsMatchIn(it.name) && !isPedal(it) }
    val pedal = usable.firstOrNull { c ->
        isPedal(c) && (throttle == null || c.sampleCount * 2 >= throttle.sampleCount)
    }
    val brake = usable.firstOrNull { BrakePattern.containsMatchIn(it.name) }
    return listOfNotNull(
        (pedal ?: throttle)?.let { Aliases.THROTTLE to it.name },
        brake?.let { Aliases.BRAKE to it.name },
    ).toMap()
}

/** A pedal channel with fewer finite samples than this is not used. */
private const val MinPedalSamples = 20
