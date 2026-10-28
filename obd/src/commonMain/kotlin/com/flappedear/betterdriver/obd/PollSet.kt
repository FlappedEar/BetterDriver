package com.flappedear.betterdriver.obd

/**
 * Which of the car's channels are read, and how the adapter's time is shared between them (FBBD-283).
 * An ELM327 answers one request at a time, so every channel read costs the others: on Arek's Wi-Fi
 * clone, which takes one PID a request at about 230 ms, the 14 channels the Corolla has gave 0.4 Hz
 * each. Reading only what is used gives that time to the channels that matter.
 *
 * [ids] are the channels read, by [PidChannel.id]; null reads every channel the car has. With
 * [pedal] the first of [Pedals] the car has is read too, so the driver's accelerator input is
 * there whichever PID the car reports it on. [fastWeight] and [slowMaxHz] go to the [PollScheduler].
 * [broadcastCar] says the session's car is one whose CAN broadcasts are listened for (the FR-S,
 * FBBD-263, FBBD-284); when false the poller asks the car's VIN. [pids], when not null, are the
 * standard mode 01 PIDs the driver switched on for the car (FBBD-344, UI-301 B): they decide which
 * standard channels are read, in place of [ids] and [pedal], which then apply to the rest only.
 */
data class PollSet(
    val ids: Set<String>?,
    val pedal: Boolean = false,
    val fastWeight: Int = PollScheduler.DEFAULT_FAST_WEIGHT,
    val slowMaxHz: Double = PollScheduler.DEFAULT_SLOW_MAX_HZ,
    val broadcastCar: Boolean = false,
    val pids: Set<Int>? = null,
) {
    /**
     * The channels of [channels] (what the car has) this set reads, in the order given. The driver's
     * own parameters ([OwnChannels], FBBD-344) are always read: they were added to be recorded.
     */
    fun select(channels: List<PidChannel>): List<PidChannel> {
        if (ids == null && pids == null) return channels
        val pedalId = if (pedal && pids == null) Pedals.firstOrNull { id -> channels.any { it.id == id } } else null
        return channels.filter { c ->
            when {
                OwnChannels.isOwn(c.id) -> true
                pids != null && c.isStandard -> c.pid in pids
                else -> ids == null || c.id in ids || c.id == pedalId
            }
        }
    }

    /**
     * The standard PIDs of [supported] this set reads (FBBD-344): where the switches on the car's
     * page start before the driver turns any of them (UI-301 B).
     */
    fun standardPids(supported: Set<Int>): Set<Int> =
        select(StandardPids.all.filter { it.pid in supported }).mapTo(LinkedHashSet()) { it.pid }

    /** This set plus [more] channel ids, for example the OBD channels shown on track screen tiles. */
    fun plus(more: Collection<String>): PollSet = if (ids == null) this else copy(ids = ids + more)

    companion object {
        /** Every channel the car has, shared out as before FBBD-283. The OBD page outside a session reads this. */
        val All = PollSet(ids = null)

        /**
         * What Arek uses (2026-10-21: "Pedał przyspieszenia/hamulca, Obroty, Temperatury"): the
         * accelerator pedal, the brake (FR-S profile) and engine speed, read fast; coolant, oil,
         * intake air, ambient and ATF (FR-S) temperatures, read at most [DRIVER_SLOW_MAX_HZ]. Speed
         * comes from the GNSS receiver. Steering angle is a CAN listen (FBBD-263), not a PID.
         */
        val Driver = PollSet(
            ids = setOf("rpm", "brake", "coolant_temp", "oil_temp", "intake_air_temp", "ambient_temp", "atf_temp"),
            pedal = true,
            fastWeight = DRIVER_FAST_WEIGHT,
            slowMaxHz = DRIVER_SLOW_MAX_HZ,
        )

        /**
         * Accelerator inputs, best first: the relative pedal position, pedal sensor D, sensor E (a
         * second sensor on the same pedal), and the throttle plate when the car reports no pedal.
         */
        val Pedals = listOf("accelerator", "pedal_d", "pedal_e", "throttle")

        /**
         * Temperatures change over minutes: one reading every 2 s is plenty, and the rest of the
         * adapter's time goes to the pedal and engine speed.
         */
        const val DRIVER_SLOW_MAX_HZ = 0.5

        /**
         * A fast request is sent this many times as often as a slow one when the adapter is too slow
         * for [DRIVER_SLOW_MAX_HZ] to apply (the Wi-Fi clone, one PID a request).
         */
        const val DRIVER_FAST_WEIGHT = 10
    }
}
