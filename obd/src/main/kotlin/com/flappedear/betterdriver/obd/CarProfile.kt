package com.flappedear.betterdriver.obd

/**
 * A car's OBD-II channels (FBBD-16): the generic [StandardPids] plus the car's own [channels], usually
 * manufacturer requests (mode 21/22) to a specific ECU header. A profile channel replaces the generic
 * channel with the same [PidChannel.id], so a car whose ECU reports a PID differently can redefine it.
 */
data class CarProfile(
    val id: String,
    val name: String,
    val channels: List<PidChannel>,
) {
    /**
     * The channels to poll on this car: the generic ones the car reports in its bitmasks
     * ([supported], from [SupportedPids]), then the profile's own. Profile channels are always
     * included; one that never answers is dropped by the [PollScheduler].
     */
    fun channelsToPoll(supported: Set<Int>, generic: List<PidChannel> = StandardPids.all): List<PidChannel> {
        val own = channels.mapTo(HashSet()) { it.id }
        val base = generic.filter { it.id !in own && (!it.isStandard || it.pid in supported) }
        return base + channels
    }

    companion object {
        /** A car with no profile: the generic channels only. */
        val Generic = CarProfile("generic", "Generic OBD-II", emptyList())
    }
}

/**
 * Built-in car profiles (FBBD-16). Channels here are community RaceChrono definitions, so they are
 * written in its equation syntax; see [com.flappedear.betterdriver.obd.equation.Equation] for how
 * `raw` and `A` index the response.
 */
object CarProfiles {
    /**
     * 2013 Scion FR-S (also Toyota 86 / GT86 and Subaru BRZ of 2012-2016, FA20, CAN 11-bit).
     *
     * - Brake: 7B0 (ABS/VSC) 21 41, `MIN(100, 1.1*MAX(A-10, 0))`: A less a resting offset of 10,
     *   scaled to percent and capped at 100.
     * - Accelerator: 01 5A with the default header, `MIN(A, 100)`: A read as percent, capped at 100,
     *   in place of the generic A·100/255.
     * - Engine oil temperature: 7E0 (engine) 21 01, `BytesToUInt(raw, 28, 1) - 40`. The well-known
     *   Torque definition is `AC-40` on the same request (https://forum.autosportlabs.com/viewtopic.php?p=25811);
     *   Torque's `AC` is the 29th data byte after the `61 01` echo, which RaceChrono users write as
     *   raw offset 28 because RaceChrono has no `AC` and its `raw` starts after the echo too
     *   (https://racechrono.com/forum/discussion/comment/10650; `A` = `bytesToUint(raw, 0, 1)` per
     *   https://racechrono.com/support/equations). So offset 28 does not count the echo. One user in
     *   that thread reports offset 34 working on their car instead; unconfirmed, to check on Arek's
     *   car with the reader (FBBD-15). It replaces the generic 01 5C oil temperature.
     * - ATF temperature: 7E1 (transmission) 21 82, `(A*256+B)*7/1800 - 40`. All integers, so like
     *   RaceChrono the division drops the fraction: whole degrees, never rounded up.
     */
    val ScionFrs2013 = CarProfile(
        id = "scion_frs_2013",
        name = "2013 Scion FR-S",
        channels = listOf(
            PidChannel("brake", "Brake", 0x7B0, 0x21, 0x41, "MIN(100, 1.1*MAX(A-10, 0))", "%", PollClass.FAST),
            PidChannel("accelerator", "Accelerator", null, 0x01, 0x5A, "MIN(A, 100)", "%", PollClass.FAST),
            PidChannel(
                "oil_temp", "Engine oil temp", 0x7E0, 0x21, 0x01, "BytesToUInt(raw, 28, 1) - 40", "°C", PollClass.SLOW,
            ),
            PidChannel("atf_temp", "ATF temp", 0x7E1, 0x21, 0x82, "(A*256+B)*7/1800 - 40", "°C", PollClass.SLOW),
        ),
    )

    val all: List<CarProfile> = listOf(CarProfile.Generic, ScionFrs2013)
}
