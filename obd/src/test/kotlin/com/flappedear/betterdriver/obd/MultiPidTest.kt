@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FBBD-270: several mode 01 PIDs in one request. */
class MultiPidTest {
    /** What Arek's Corolla is polled for (his 16:23 log of 2026-10-20), in StandardPids order. */
    private val corolla = listOf(
        "engine_load", "coolant_temp", "rpm", "speed", "timing_advance", "intake_air_temp", "maf", "throttle",
        "module_voltage", "commanded_lambda", "ambient_temp", "pedal_d", "pedal_e", "oil_temp",
    ).map { id -> StandardPids.all.single { it.id == id } }

    @Test
    fun `driver inputs share the first request, fast and slow are kept apart, six PIDs at most`() {
        val groups = MultiPid.groups(corolla).map { g -> g.map { it.id } }
        assertEquals(
            listOf(
                listOf("rpm", "speed", "throttle", "pedal_d", "pedal_e", "engine_load"),
                listOf("coolant_temp", "intake_air_temp", "module_voltage", "ambient_temp", "oil_temp"),
                listOf("timing_advance", "maf", "commanded_lambda"),
            ),
            groups,
        )
        assertEquals("010C0D11494A04", MultiPid.command(MultiPid.groups(corolla).first()))
        assertEquals(corolla.size, MultiPid.groups(corolla, combine = false).size)
    }

    @Test
    fun `channels with a header or another mode are asked on their own`() {
        val frs = CarProfiles.ScionFrs2013.channels
        val groups = MultiPid.groups(frs + StandardPids.Rpm + StandardPids.Speed)
        val brake = frs.single { it.id == "brake" }
        assertTrue(listOf(brake) in groups)
        // accelerator is a plain mode 01 PID (5A) in the FR-S profile, so it joins rpm and speed.
        val accelerator = frs.single { it.id == "accelerator" }
        assertTrue(listOf(StandardPids.Rpm, StandardPids.Speed, accelerator) in groups, groups.toString())
        assertEquals("2141", MultiPid.command(listOf(brake)))
    }

    @Test
    fun `a multi-PID answer splits into single-PID answers`() {
        val parts = MultiPid.split(hexBytes("410C1AF80D32117F5C7D")!!)
        assertEquals(listOf(0x0C, 0x0D, 0x11, 0x5C), parts.keys.toList())
        assertEquals(1726.0, StandardPids.Rpm.decode(assertNotNull(parts[0x0C])))
        assertEquals(50.0, StandardPids.Speed.decode(assertNotNull(parts[0x0D])))
        assertEquals(85.0, StandardPids.OilTemp.decode(assertNotNull(parts[0x5C])))
        // An ECU leaves out the PIDs it does not have; a PID of unknown length ends the split.
        assertEquals(listOf(0x0D), MultiPid.split(hexBytes("410D32")!!).keys.toList())
        assertEquals(listOf(0x0D), MultiPid.split(hexBytes("410D32A0FFFF")!!).keys.toList())
        assertEquals(emptyMap(), MultiPid.split(hexBytes("7F0112")!!))
    }
}
