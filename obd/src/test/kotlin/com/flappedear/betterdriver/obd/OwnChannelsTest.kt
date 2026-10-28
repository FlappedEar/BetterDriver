@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile as SetupCar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-344: the driver's own parameters and what a scan finds. */
class OwnChannelsTest {
    @Test
    fun `an own request is a channel with its own id`() {
        val c = assertNotNull(OwnChannels.pid("trim", "Fuel trim", 0x7E0, 0x01, 0x06, "(A-128)*100.0/128", "%", false))
        assertEquals("custom_trim", c.id)
        assertEquals("0106", c.command)
        assertEquals(PollClass.SLOW, c.pollClass)
        assertEquals(25.0, c.decode(byteArrayOf(0x41, 0x06, 0xA0.toByte())))
        val mode22 = assertNotNull(OwnChannels.pid("x", "X", 0x7E0, 0x22, 0xF190, "A", "", true, pidLength = 2))
        assertEquals("22F190", mode22.command)
    }

    @Test
    fun `an invalid parameter is left out, never thrown`() {
        assertNull(OwnChannels.pid("a", "A", null, 0x01, 0x0C, "A +", "", false), "equation")
        assertNull(OwnChannels.pid("a", "A", null, 0x00, 0x0C, "A", "", false), "mode")
        assertNull(OwnChannels.pid("a", "A", null, 0x3F, 0x0C, "A", "", false), "broadcast mode")
        assertNull(OwnChannels.pid("a", "A", null, 0x01, 0x100, "A", "", false), "pid")
        assertNull(OwnChannels.pid("a", "A", null, 0x22, 0x10000, "A", "", false, pidLength = 2), "pid 22")
        assertNull(OwnChannels.pid("a", "A", null, 0x22, 1, "A", "", false, pidLength = 3), "length")
        assertNull(OwnChannels.pid("a", "A", -1, 0x01, 0x0C, "A", "", false), "header")
        assertNull(OwnChannels.can("a", "A", 0x800, "A", ""), "29-bit id")
        assertNull(OwnChannels.can("a", "A", 0x0D4, "bytesToNothing(raw, 0, 1)", ""), "function")
        assertNotNull(OwnChannels.can("a", "A", 0x0D4, "bytesToUIntLe(raw, 4, 2) * 0.015694", "km/h"))
    }

    @Test
    fun `own channels are read whatever the poll set`() {
        val own = OwnChannels.pid("trim", "Trim", null, 0x01, 0x06, "A", "", false)!!
        val picked = PollSet.Driver.select(listOf(StandardPids.Rpm, StandardPids.Maf, own))
        assertEquals(listOf("rpm", "custom_trim"), picked.map { it.id })
    }

    @Test
    fun `a car's stored parameters make its profile`() {
        val car = SetupCar(
            "c", "FR-S",
            parameters = listOf(
                CarParameter("trim", "Trim", CarParameterSource.OBD, "A", "%", header = 0x7E0, pid = 0x06),
                CarParameter("speed_rl", "Wheel RL", CarParameterSource.CAN, "bytesToUIntLe(raw, 4, 2)", canId = 0x0D4),
                CarParameter("bad", "Bad", CarParameterSource.CAN, "A +", canId = 0x0D4),
            ),
        )
        val p = ownProfile(car)
        assertEquals(CarProfile.Generic.id, p.id)
        assertEquals(listOf("custom_trim"), p.channels.map { it.id })
        assertEquals(0x7E0, p.channels.single().header)
        assertEquals(listOf("custom_speed_rl"), p.signals.map { it.id })
        assertEquals(CarProfile.Generic, ownProfile(null))
    }

    @Test
    fun `a scan lists each id with its rate and the bytes that move`() {
        val frames = listOf(
            0x0D4 to byteArrayOf(1, 0, 3, 4),
            0x0D4 to byteArrayOf(1, 9, 3, 5),
            0x360 to byteArrayOf(0x75, 0x7D),
            0x0D4 to byteArrayOf(1, 9, 3, 6),
        )
        val ids = CarScan.canIds(frames, ms = 500)
        assertEquals(listOf(0x0D4, 0x360), ids.map { it.id })
        val d4 = ids[0]
        assertEquals(3, d4.frames)
        assertEquals(6.0, d4.perSecond)
        assertEquals(4, d4.length)
        assertEquals(setOf(1, 3), d4.changing)
        assertEquals("01090306", d4.last)
        assertTrue(ids[1].changing.isEmpty())
        val record = CarScan("A6", setOf(0x0C, 0x05), "JF1ZNAA1", ids, canHeard = true).toRecord(1_000)
        assertEquals(listOf(0x05, 0x0C), record.supported)
        assertEquals(listOf(1, 3), record.canIds[0].changing)
    }

    @Test
    fun `the PIDs switched on decide the standard channels`() {
        // UI-301 B: MAF on and coolant off; the FR-S brake request (not a standard PID) follows the ids.
        val set = PollSet.Driver.copy(pids = setOf(0x0C, 0x10))
        val channels = listOf(StandardPids.Rpm, StandardPids.Coolant, StandardPids.Maf, StandardPids.Accelerator) +
            CarProfiles.ScionFrs2013.channels.filter { it.id == "brake" }
        assertEquals(listOf("rpm", "maf", "brake"), set.select(channels).map { it.id })
        // Before any switch: what the app reads now, the first pedal among them.
        assertEquals(setOf(0x05, 0x0C, 0x5A), PollSet.Driver.standardPids(setOf(0x05, 0x0C, 0x10, 0x11, 0x5A)))
        assertEquals(setOf(0x05, 0x0C, 0x11), PollSet.Driver.standardPids(setOf(0x05, 0x0C, 0x10, 0x11)))
    }

    @Test
    fun `a car's first PID switch starts from what was recorded`() {
        val car = SetupCar("c", "FR-S")
        val off = car.recordPid(0x05, on = false, current = setOf(0x05, 0x0C))
        assertEquals(listOf(0x0C), off.recordedPids)
        assertEquals(listOf(0x0C, 0x10), off.recordPid(0x10, on = true, current = emptySet()).recordedPids)
    }
}
