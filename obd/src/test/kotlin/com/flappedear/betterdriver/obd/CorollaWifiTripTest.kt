@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-15 on real data: Arek's 2023 Corolla through a Wi-Fi ELM327 v1.5 clone (protocol A6, CAN
 * 11-bit 500k), from his log of 2026-10-20 16:23 (two drives, 60 min). Only what the adapter and car
 * answered and the rates the session report measured; no positions.
 */
class CorollaWifiTripTest {
    /** `car answered: ... PIDs ...` from the log: the union of the car's bitmask answers. */
    private val supported: Set<Int> = (
        "01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,1C,1F,20,21,2E,30,31,33,34,35,3C,3E,40,41,42,43,44,45," +
            "46,47,49,4A,4C,4D,4E,51,55,56,5B,5C,60,62,63,65,69,6D,7A,80,87,8E,9A,9D,9E,A0,A6"
        ).split(',').map { it.toInt(16) }.toSet()

    /** `... polling ...` from the same line, in the order logged. */
    private val polled = listOf(
        "engine_load", "coolant_temp", "rpm", "speed", "timing_advance", "intake_air_temp", "maf", "throttle",
        "module_voltage", "commanded_lambda", "ambient_temp", "pedal_d", "pedal_e", "oil_temp",
    )

    private val channels = CarProfile.Generic.channelsToPoll(supported).filter { it.header == null }

    @Test
    fun `the generic profile polls what the car logged, oil temperature from 01 5C included`() {
        assertEquals(polled, channels.map { it.id })
        val oil = channels.single { it.id == "oil_temp" }
        assertEquals(Request(null, 0x01, 0x5C), oil.request)
        assertEquals(PollClass.SLOW, oil.pollClass)
    }

    @Test
    fun `discovery asks 01 00 to 01 A0 and stops there`() {
        val asked = generateSequence(SupportedPids.bitmaskPids.first()) { SupportedPids.nextBitmask(it, supported) }
        assertEquals(listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0), asked.toList())
    }

    @Test
    fun `the scheduler gives what the trip measured at 230 ms a request`() {
        // Session report, drive 1 (26 min): fast channels 0.4248-0.4251 Hz, slow 0.0850-0.0851 Hz.
        // rates_hz latency_ms over both drives: 212-304, typically about 230.
        val measuredFastHz = 0.425
        val measuredSlowHz = 0.085
        val latencyMs = 230L
        val scheduler = PollScheduler(channels, rateWindowMs = 600_000)
        var now = 0L
        while (now < 600_000) {
            val next = assertNotNull(scheduler.next(now))
            now = maxOf(now, next.notBeforeMs)
            scheduler.completed(next.request, now, latencyMs, answered = true)
            now += latencyMs
        }
        val predicted = scheduler.predictedHz(latencyMs.toDouble())
        for (channel in channels) {
            val measured = if (channel.pollClass == PollClass.FAST) measuredFastHz else measuredSlowHz
            val achieved = scheduler.achievedHz(channel, now)
            val expected = predicted.getValue(channel.request)
            // FBBD-15: the scheduler's rate within 10 % of the rate measured from the responses.
            assertTrue(kotlin.math.abs(expected - measured) <= 0.1 * measured, "${channel.id} $expected vs $measured")
            assertTrue(kotlin.math.abs(achieved - measured) <= 0.1 * measured, "${channel.id} $achieved vs $measured")
        }
    }
}
