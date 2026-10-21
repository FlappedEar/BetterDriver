package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals

/** FBBD-283: which of the car's channels each poll set reads. */
class PollSetTest {
    /** The 14 channels Arek's Corolla has (log of 2026-10-20 16:23). */
    private val corolla = listOf(
        "engine_load", "coolant_temp", "rpm", "speed", "timing_advance", "intake_air_temp", "maf", "throttle",
        "module_voltage", "commanded_lambda", "ambient_temp", "pedal_d", "pedal_e", "oil_temp",
    ).map { id -> StandardPids.all.single { it.id == id } }

    @Test
    fun `all reads every channel`() {
        assertEquals(corolla, PollSet.All.select(corolla))
    }

    @Test
    fun `the driver set on the Corolla is rpm, one pedal and the temperatures`() {
        val ids = PollSet.Driver.select(corolla).map { it.id }
        assertEquals(listOf("coolant_temp", "rpm", "intake_air_temp", "ambient_temp", "pedal_d", "oil_temp"), ids)
    }

    @Test
    fun `the relative pedal is preferred, and the throttle plate stands in when there is no pedal`() {
        val withAccelerator = corolla + StandardPids.Accelerator
        assertEquals(
            listOf("accelerator"),
            PollSet.Driver.select(withAccelerator).map { it.id }.filter { it in PollSet.Pedals },
        )
        val noPedal = corolla.filter { it.id != "pedal_d" && it.id != "pedal_e" }
        assertEquals(
            listOf("throttle"),
            PollSet.Driver.select(noPedal).map { it.id }.filter { it in PollSet.Pedals },
        )
    }

    @Test
    fun `channels shown on tiles are added`() {
        val ids = PollSet.Driver.plus(listOf("maf", "speed")).select(corolla).map { it.id }
        assertEquals(
            listOf("coolant_temp", "rpm", "speed", "intake_air_temp", "maf", "ambient_temp", "pedal_d", "oil_temp"),
            ids,
        )
    }

    @Test
    fun `adding to all changes nothing`() {
        assertEquals(PollSet.All, PollSet.All.plus(listOf("maf")))
    }
}
