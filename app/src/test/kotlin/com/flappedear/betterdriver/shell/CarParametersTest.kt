package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.CarScanRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FBBD-344: the steps' fields, the scan's texts and what the car page names. */
class CarParametersTest {
    @Test
    fun `a CAN draft becomes a parameter once every step is valid`() {
        val d = ParameterDraft.can(0x0D4)
        assertEquals("0D4", d.canId)
        assertTrue(d.sourceValid)
        assertFalse(d.equationValid)
        assertNull(d.toParameter("k"))
        val full = d.copy(equation = "bytesToUIntLe(raw, 4, 2) * 0.015694", unit = "km/h", name = " Wheel RL ")
        val p = full.toParameter("wheel_rl")!!
        val want = CarParameter("wheel_rl", "Wheel RL", CarParameterSource.CAN, full.equation, "km/h", canId = 0x0D4)
        assertEquals(want, p)
        assertEquals(full, ParameterDraft.of(p).copy(name = " Wheel RL "))
        assertFalse(d.copy(canId = "800").sourceValid)
        assertFalse(d.copy(canId = "XYZ").sourceValid)
    }

    @Test
    fun `an OBD draft reads its header, mode and PID`() {
        val d = ParameterDraft(CarParameterSource.OBD, header = "7E0", request = "01 06", equation = "A-40", name = "T")
        val p = d.toParameter("t")!!
        assertEquals(0x7E0, p.header)
        assertEquals(0x01, p.mode)
        assertEquals(0x06, p.pid)
        assertEquals(1, p.pidLength)
        val mode22 = d.copy(header = "", request = "22F190").toParameter("v")!!
        assertEquals(null, mode22.header)
        assertEquals(0xF190, mode22.pid)
        assertEquals(2, mode22.pidLength)
        assertFalse(d.copy(request = "0106A").sourceValid)
        assertFalse(d.copy(request = "3F 00").sourceValid)
        assertFalse(d.copy(header = "7G0").sourceValid)
        assertEquals("OBD 7E0 01 06 · A-40", parameterSource(p))
        assertEquals(ParameterDraft.pid(0x1F).request, "01 1F")
    }

    @Test
    fun `the formula is checked on the scan's last frame`() {
        val frame = CanIdRecord(0x0D4, 50.0, 8, listOf(4, 5), "1A003C00E80F007B")
        val d = ParameterDraft.can(0x0D4).copy(equation = "bytesToUIntLe(raw, 4, 2)")
        assertEquals(0x0FE8.toDouble(), d.valueOn(frame)!!, 1e-9)
        assertNull(d.copy(equation = "A +").valueOn(frame))
        assertNull(d.valueOn(null))
    }

    @Test
    fun `the bytes that move read as ranges`() {
        assertEquals("bytes 0–1, 4 change", changingText(listOf(4, 0, 1)))
        assertEquals("byte 2 changes", changingText(listOf(2)))
        assertEquals("no byte changed", changingText(emptyList()))
    }

    @Test
    fun `the bitmask PIDs are not listed`() {
        val scan = CarScanRecord(0, "A6", listOf(0x00, 0x05, 0x20, 0x42))
        assertEquals(listOf(0x05, 0x42), reportedPids(scan))
        assertEquals("the adapter would not listen", noCanReason(scan))
        assertEquals("the car is not on 11-bit CAN", noCanReason(scan.copy(protocol = "A7")))
    }

    @Test
    fun `CAN ids are named by what reads them`() {
        val car = CarProfile(
            "c", "Corolla",
            parameters = listOf(CarParameter("g", "Gear", CarParameterSource.CAN, "A", canId = 0x3B7)),
        )
        assertEquals(listOf("Gear"), canIdNames(car, 0x3B7, frsCar = false))
        assertEquals(emptyList<String>(), canIdNames(car, 0x0D0, frsCar = false))
        assertEquals(listOf("Steering angle"), canIdNames(car, 0x0D0, frsCar = true))
        assertFalse(readsFrsBroadcasts(car, nameSaysSo = false))
        val frs = car.copy(scan = CarScanRecord(0, "A6", emptyList(), vinPrefix = "JF1ZNAA1"))
        assertTrue(readsFrsBroadcasts(frs, nameSaysSo = false))
    }

    @Test
    fun `a scan time says today or the date`() {
        val day = 86_400_000L
        val at = 1_761_660_000_000L
        assertTrue(scanTime(at, at + 1).startsWith("today "))
        assertFalse(scanTime(at, at + 3 * day).startsWith("today"))
    }
}
