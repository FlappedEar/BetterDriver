@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.diy

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiyGpsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `UUIDs are the 16-bit ones of the API on the Bluetooth base`() {
        assertEquals(UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb"), DiyGatt.SERVICE)
        assertEquals(UUID.fromString("00000003-0000-1000-8000-00805f9b34fb"), DiyGatt.GPS_MAIN)
        assertEquals(UUID.fromString("00000004-0000-1000-8000-00805f9b34fb"), DiyGatt.GPS_TIME)
        assertEquals(UUID.fromString("00000001-0000-1000-8000-00805f9b34fb"), DiyGatt.CAN_MAIN)
        assertEquals(listOf(DiyGatt.GPS_MAIN, DiyGatt.GPS_TIME), DiyGatt.PROFILE.notify)
        assertEquals(setOf(DiyGatt.GPS_TIME), DiyGatt.PROFILE.optional)
    }

    @Test
    fun `GPS main, byte by byte`() {
        // Sync 5, 12:34.568 into the hour: 12*30000 + 34*500 + 568/2 = 377284 = 0x05C1C4, sync in the top 3 bits.
        val b = bytes(
            0xA5, 0xC1, 0xC4,
            0x4C, // fix quality 1, 12 satellites
            0x1D, 0xEE, 0xCC, 0x87, // 50.2189191 deg
            0xF4, 0xA6, 0x2D, 0x60, // -19.0436 deg
            0x1D, 0x52, // (250.6 + 500) * 10 = 7506
            0x2E, 0xE0, // 120.00 km/h
            0x46, 0x6A, // 180.26 deg
            0x08, 0x0F, // HDOP 0.8, VDOP 1.5
        )
        val m = parseDiyGpsMain(b)!!
        assertEquals(5, m.sync)
        assertEquals(12 * 60_000L + 34_568, m.timeInHourMs)
        assertEquals(1, m.fixQuality)
        assertEquals(12, m.satellites)
        assertEquals(50.2189191, m.latDeg!!, 1e-9)
        assertEquals(-19.0436, m.lonDeg!!, 1e-9)
        assertEquals(250.6, m.altitudeM!!, 1e-9)
        assertEquals(120.0, m.speedKmh!!, 1e-9)
        assertEquals(180.26, m.bearingDeg!!, 1e-9)
        assertEquals(0.8, m.hdop!!, 1e-9)
        assertEquals(1.5, m.vdop!!, 1e-9)
        assertTrue(m.hasFix)
    }

    @Test
    fun `coarse altitude and speed above the fine ranges`() {
        val m = parseDiyGpsMain(DiyTestData.main(0, 0, 0, 0, 1, 8, 1.0, 2.0, 3000.0, 400.0, 0.0, 1.0, 1.0))!!
        assertEquals(3000.0, m.altitudeM!!, 0.0)
        assertEquals(400.0, m.speedKmh!!, 0.0)
        val low = parseDiyGpsMain(DiyTestData.main(0, 0, 0, 0, 1, 8, 1.0, 2.0, -20.5, 327.67, 0.0, 1.0, 1.0))!!
        assertEquals(-20.5, low.altitudeM!!, 1e-9)
        assertEquals(327.67, low.speedKmh!!, 1e-9)
    }

    @Test
    fun `invalid values are null and there is no fix without a position or quality`() {
        val m = parseDiyGpsMain(DiyTestData.main(7, 59, 59, 998, 0, null, null, null, null, null, null, null, null))!!
        assertEquals(7, m.sync)
        assertEquals(3_599_998L, m.timeInHourMs)
        assertNull(m.satellites)
        assertNull(m.latDeg)
        assertNull(m.lonDeg)
        assertNull(m.altitudeM)
        assertNull(m.speedKmh)
        assertNull(m.bearingDeg)
        assertNull(m.hdop)
        assertNull(m.vdop)
        assertFalse(m.hasFix)
        val noQuality = parseDiyGpsMain(DiyTestData.main(0, 0, 0, 0, 0, 5, 1.0, 2.0, 0.0, 0.0, 0.0, 1.0, 1.0))!!
        assertFalse(noQuality.hasFix)
    }

    @Test
    fun `a time past the hour and short values are rejected`() {
        val b = DiyTestData.main(0, 0, 0, 0, 1, 5, 1.0, 2.0, 0.0, 0.0, 0.0, 1.0, 1.0)
        b[0] = 0x1F
        b[1] = 0xFF.toByte()
        b[2] = 0xFF.toByte()
        assertNull(parseDiyGpsMain(b)!!.timeInHourMs)
        assertNull(parseDiyGpsMain(ByteArray(19)))
        assertNull(parseDiyGpsTime(ByteArray(2)))
    }

    @Test
    fun `GPS time, byte by byte`() {
        // 2026-10-11 14:00 UTC: 26*8928 + 9*744 + 10*24 + 14 = 239078 = 0x03A5E6, sync 5.
        val t = parseDiyGpsTime(bytes(0xA3, 0xA5, 0xE6))!!
        assertEquals(5, t.sync)
        assertEquals(Instant.parse("2026-10-11T14:00:00Z").toEpochMilli(), t.hourStartUtcMs)
        val built = parseDiyGpsTime(DiyTestData.time(2, 2024, 2, 29, 23))!!
        assertEquals(2, built.sync)
        assertEquals(Instant.parse("2024-02-29T23:00:00Z").toEpochMilli(), built.hourStartUtcMs)
    }

    @Test
    fun `GPS time that is not a real date has no hour`() {
        assertNull(parseDiyGpsTime(DiyTestData.time(0, 2025, 2, 30, 0))!!.hourStartUtcMs)
    }
}
