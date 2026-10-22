package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet

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

/** Turns a session's sample sets, in time order, into coach fixes carrying the last pedal value from [pedal]. */
class CoachFixReader(val pedal: Int?) {
    private var value: Double? = null
    private var at = Long.MIN_VALUE

    /** The fix in [set], if it has one, with the pedal when one was read in the last [MaxPedalAgeMicros]. */
    fun read(set: SampleSet): CoachFix? {
        if (pedal != null) {
            set.values.firstOrNull { it.channelId == pedal }?.value?.takeIf { it.isFinite() }?.let {
                value = it
                at = set.tMicros
            }
        }
        val fix = set.toCoachFix() ?: return null
        val fresh = value?.takeIf { set.tMicros - at in 0..MaxPedalAgeMicros }
        return if (fresh == null) fix else fix.copy(throttlePct = fresh)
    }
}
