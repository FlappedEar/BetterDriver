package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData

// The driver's pedal for the coach (FBBD-307): where the throttle goes back on in a corner cannot be
// read from the speed (the car still slows on a part throttle), so the coach takes it from OBD, as
// the lap analysis does (FBBD-203). The pedal arrives in its own sample sets at its own rate; each
// fix carries the last value, while it is recent.

/** A throttle channel: the app's OBD "obd_throttle", RaceChrono's "throttle_pos-obd" (as PedalAliases). */
private val ThrottlePattern = Regex("throttle|accelerator.?pedal", RegexOption.IGNORE_CASE)

/** The accelerator pedal itself, preferred over the throttle plate as the driver's input. */
private val AcceleratorPattern =
    Regex("^(?:obd_)?accelerator(?:.?(?:pedal|pos))?|^(?:obd_)?pedal_[de]$", RegexOption.IGNORE_CASE)

/** A pedal value older than this at a fix is not used: OBD runs at 7 to 20 Hz on the Vgate (FBBD-270). */
const val MaxPedalAgeMicros = 500_000L

/** The channel the coach reads the pedal from: the accelerator pedal, else the throttle; null without either. */
fun pedalChannelId(channels: Collection<ChannelDef>): Int? {
    val sorted = channels.sortedBy { it.name.lowercase() }
    return (
        sorted.firstOrNull { AcceleratorPattern.containsMatchIn(it.name) }
            ?: sorted.firstOrNull { ThrottlePattern.containsMatchIn(it.name) }
        )?.id
}

/**
 * The car's steering wheel angle (FBBD-321): the app's "obd_steering_angle" from the FR-S's CAN,
 * RaceChrono's "steering_angle-canbus".
 */
private val SteeringPattern = Regex("^(?:obd_)?steering(?:_wheel)?_angle(?:-obd|-canbus)?$", RegexOption.IGNORE_CASE)

/** The channel the coach reads the steering wheel from; null without one. */
fun steeringChannelId(channels: Collection<ChannelDef>): Int? =
    channels.sortedBy { it.name.lowercase() }.firstOrNull { SteeringPattern.matches(it.name) }?.id

/**
 * The engine speed (FBBD-326): the app's "obd_rpm" (OBD or the FR-S's CAN), RaceChrono's "rpm-obd" or
 * "rpm-canbus".
 */
private val RpmPattern = Regex("^(?:obd_)?(?:engine_)?rpm(?:-obd|-canbus)?$", RegexOption.IGNORE_CASE)

/** The channel the coach reads the engine speed from; null without one. */
fun rpmChannelId(channels: Collection<ChannelDef>): Int? =
    channels.sortedBy { it.name.lowercase() }.firstOrNull { RpmPattern.matches(it.name) }?.id

/** An engine speed older than this is not the car's now: OBD over Wi-Fi gives a few a second (FBBD-326). */
const val MaxRpmAgeMicros = 1_000_000L

/**
 * Turns a session's sample sets, in time order, into coach fixes carrying the last pedal value from
 * [pedal], the last steering wheel angle from [steering] (FBBD-321) and the last engine speed from
 * [rpm] (FBBD-326), each while recent.
 */
class CoachFixReader(val pedal: Int?, val steering: Int? = null, val rpm: Int? = null) {
    private val pedalValue = Latest(pedal)
    private val steeringValue = Latest(steering)
    private val rpmValue = Latest(rpm, MaxRpmAgeMicros)

    /** The fix in [set], if it has one, with the pedal and the wheel when read in the last [MaxPedalAgeMicros]. */
    fun read(set: SampleSet): CoachFix? {
        pedalValue.read(set)
        steeringValue.read(set)
        rpmValue.read(set)
        val fix = set.toCoachFix() ?: return null
        return fix.copy(
            throttlePct = pedalValue.at(set.tMicros) ?: fix.throttlePct,
            steerDeg = steeringValue.at(set.tMicros) ?: fix.steerDeg,
            rpm = rpmValue.at(set.tMicros) ?: fix.rpm,
        )
    }

    /** The last finite value of one channel and when it came, kept for [maxAgeMicros]. */
    private class Latest(val channel: Int?, val maxAgeMicros: Long = MaxPedalAgeMicros) {
        private var value: Double? = null
        private var at = Long.MIN_VALUE

        fun read(set: SampleSet) {
            if (channel == null) return
            set.values.firstOrNull { it.channelId == channel }?.value?.takeIf { it.isFinite() }?.let {
                value = it
                at = set.tMicros
            }
        }

        fun at(tMicros: Long): Double? = value?.takeIf { tMicros - at in 0..maxAgeMicros }
    }
}

/** The session's fixes, in time order, with the pedal where it recorded one (FBBD-307). */
fun SessionData.coachFixes(): Sequence<CoachFix> {
    val reader = CoachFixReader(
        pedalChannelId(channels.values), steeringChannelId(channels.values), rpmChannelId(channels.values),
    )
    return sampleSets.asSequence().mapNotNull(reader::read)
}
