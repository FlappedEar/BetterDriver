package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.corners.PedalScale
import com.flappedear.betterdriver.analysis.corners.throttleScale
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.analysis.vehicle.vehicleChannel
import com.flappedear.betterdriver.timing.gear.DriveSample
import com.flappedear.betterdriver.timing.gear.Gearbox

// The gear in each corner (FBBD-326): the car's gearbox from the sessions at the track (engine speed
// against road speed, how hard each gear pulls at full throttle), the gear of every lap at each
// corner's slowest point, and where a lower gear pulls harder from there. Arek's picks on the UI
// review page, round 56: he drives the FR-S's automatic in M with the paddles (UI-278 A), the coach
// says the gear with the brake cue (UI-279 A) and the Map tab gives the gear with the reason in his
// own laps (UI-280 A). The advice comes from the car, not from a pass he drove: at Silesia some
// corners were in 3rd on every one of his fastest passes.

/**
 * One lap's slowest point in one corner: its speed and engine speed, the speed [ExitMeters] later
 * and the time through the window.
 */
internal class ApexReading(val apexKmh: Double, val rpm: Double?, val exitKmh: Double?, val seconds: Double)

/** The speed is compared this far after the slowest point, as Arek's Silesia laps were (FBBD-326). */
const val ExitMeters = 100.0

/** A corner gets a gear only from this many laps with an engine speed at its slowest point. */
const val MinGearLaps = 3

/** A gear this share of the laps were in at a corner is one he uses there, not a one-off. */
const val MinHigherShare = 0.25

/**
 * Where a lower gear pulls harder (FBBD-326): be in [gear] at the slowest point instead of [from],
 * which the analysed lap or at least [MinHigherShare] of the laps were in. [higherLaps] of [laps]
 * laps over the sessions were in [from] or above. [lowerExitKmh] and [higherExitKmh] are the median
 * speed [ExitMeters] after the slowest point of the laps in [gear] and of those in [from] (null
 * without at least two of each), [lowerPullG] and [higherPullG] how hard each gear pulls at full
 * throttle at the apex speed, and [seconds] the time the laps in [gear] were faster through the
 * corner's window (null without both).
 */
data class CornerGear(
    val gear: Int,
    val from: Int,
    val apexKmh: Double,
    val higherLaps: Int,
    val laps: Int,
    val lowerExitKmh: Double?,
    val higherExitKmh: Double?,
    val lowerPullG: Double,
    val higherPullG: Double,
    val seconds: Double?,
)

/** Every reading of [session] for the gearbox: speed, engine speed and the pedal in percent, in time order. */
@Suppress("ReturnCount")
internal fun driveSamples(session: TelemetrySession): List<DriveSample> {
    val speed = session.channel(Aliases.SPEED) ?: return emptyList()
    val name = session.aliases[Aliases.SPEED] ?: return emptyList()
    val factor = metresPerSecondPerSpeedUnit(effectiveChannelUnit(session, name)) ?: return emptyList()
    val rpm = vehicleChannel(session, VehicleChannel.Rpm) ?: return emptyList()
    val pedal = session.channel(Aliases.THROTTLE)
    val pedalFactor = when (throttleScale(session)) {
        PedalScale.Percent -> 1.0
        PedalScale.Fraction -> Percent
        PedalScale.Unknown -> null
    }
    return speed.timestamps.indices.mapNotNull { i ->
        val t = speed.timestamps[i]
        val v = speed.values[i].takeIf { it.isFinite() } ?: return@mapNotNull null
        DriveSample(
            t, v * factor, rpm.valueAt(t, minimumGapSeconds = MaxRpmGapSeconds),
            pedalFactor?.let { f -> pedal?.valueAt(t)?.let { it * f } },
        )
    }
}

/**
 * The gear for the corner whose reference lap was at [apexKmh] and [rpm] at its slowest point, from
 * [readings] of every lap there (FBBD-326); null without a gearbox, with fewer than [MinGearLaps] laps
 * in a gear, or where no lower gear pulls at least [com.flappedear.betterdriver.timing.gear.MinPullGain] harder.
 */
@Suppress("ReturnCount")
internal fun cornerGear(gearbox: Gearbox?, apexKmh: Double?, rpm: Double?, readings: List<ApexReading>): CornerGear? {
    if (gearbox == null || apexKmh == null) return null
    val speed = apexKmh / KmhPerMps
    val geared = readings.mapNotNull { r -> r.rpm?.let { gearbox.gearAt(it, r.apexKmh / KmhPerMps) }?.let { r to it } }
    if (geared.size < MinGearLaps) return null
    // The gear the reference lap was in, or a higher one a good share of the laps were in: a corner
    // taken in 3rd on most laps needs 2nd even when the best lap happened to be in it.
    val own = rpm?.let { gearbox.gearAt(it, speed) }
    val usual = geared.groupingBy { it.second }.eachCount()
        .filter { it.value >= geared.size * MinHigherShare }.keys.maxOrNull()
    val from = listOfNotNull(own, usual).maxOrNull() ?: return null
    val gear = gearbox.lowerGear(from, speed) ?: return null
    val lower = geared.filter { it.second == gear }.map { it.first }
    val higher = geared.filter { it.second >= from }.map { it.first }
    fun exit(rs: List<ApexReading>) = rs.mapNotNull { it.exitKmh }.takeIf { it.size >= 2 }?.let(::median)
    val seconds = if (lower.size >= 2 && higher.size >= 2) {
        (median(higher.map { it.seconds }) - median(lower.map { it.seconds })).takeIf { it > 0.0 }
    } else {
        null
    }
    return CornerGear(
        gear, from, apexKmh, higher.size, geared.size, exit(lower), exit(higher),
        gearbox.pullAt(gear, speed)!! / StandardGravity, gearbox.pullAt(from, speed)!! / StandardGravity, seconds,
    )
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

/**
 * OBD engine speed comes a few times a second over Wi-Fi, 100 times over the FR-S's CAN; a longer
 * gap is the adapter losing it.
 */
const val MaxRpmGapSeconds = 1.0

private const val KmhPerMps = 3.6
private const val Percent = 100.0
private const val StandardGravity = 9.80665
