@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBoxDataTest {
    private fun parse(edit: (ByteArray) -> Unit = {}) =
        checkNotNull(RaceBoxData.parse(RaceBoxTestData.payload().also(edit)))

    @Test
    fun `decodes every field with the reference client's example values`() {
        val d = parse()
        assertEquals(Instant.parse("2022-01-10T08:51:08.239Z").toEpochMilli(), d.utcMillis)
        assertEquals(3, d.fixType)
        assertTrue(d.fixOk)
        assertFalse(d.headingValid)
        assertFalse(d.coordinatesInvalid)
        assertTrue(d.hasFix)
        assertEquals(11, d.satellites)
        assertEquals(42.6719035, d.latDeg, 1e-9)
        assertEquals(23.2887238, d.lonDeg, 1e-9)
        assertEquals(625.761, d.wgsAltM, 1e-9)
        assertEquals(590.095, d.mslAltM, 1e-9)
        assertEquals(0.924, d.horizontalAccM, 1e-9)
        assertEquals(1.836, d.verticalAccM, 1e-9)
        assertEquals(0.035, d.speedMps, 1e-9)
        assertEquals(0.126, d.speedKmh, 1e-9)
        assertEquals(0.0, d.headingDeg, 1e-9)
        assertEquals(0.208, d.speedAccMps, 1e-9)
        assertEquals(145.26856, d.headingAccDeg, 1e-9)
        assertEquals(3.0, d.pdop, 1e-9)
        assertEquals(12.3, d.microInputVolts, 1e-9)
        assertEquals(-0.003, d.accelXg, 1e-9)
        assertEquals(0.113, d.accelYg, 1e-9)
        assertEquals(0.974, d.accelZg, 1e-9)
        assertEquals(1.0, d.gyroXdps, 1e-9)
        assertEquals(-2.0, d.gyroYdps, 1e-9)
        assertEquals(3.0, d.gyroZdps, 1e-9)
    }

    @Test
    fun `negative coordinates are signed`() {
        // -3.7038 degrees (west of Greenwich) = -37,038,000 = 0xFDCAD850.
        val d = parse { p -> intArrayOf(0x50, 0xD8, 0xCA, 0xFD).forEachIndexed { i, b -> p[24 + i] = b.toByte() } }
        assertEquals(-3.7038, d.lonDeg, 1e-7)
    }

    @Test
    fun `heading uses 1e-5 degrees`() {
        // 271.5 degrees = 27,150,000 = 0x019E46B0.
        val d = parse { p ->
            intArrayOf(0xB0, 0x46, 0x9E, 0x01).forEachIndexed { i, b -> p[52 + i] = b.toByte() }
            p[21] = 0x21
        }
        assertEquals(271.5, d.headingDeg, 1e-9)
        assertTrue(d.headingValid)
    }

    @Test
    fun `no UTC time unless date and time are valid`() {
        assertNull(parse { it[11] = 0x01 }.utcMillis)
        assertNull(parse { it[11] = 0x02 }.utcMillis)
    }

    @Test
    fun `impossible date gives no UTC time instead of failing`() {
        assertNull(parse { it[6] = 13 }.utcMillis)
    }

    @Test
    fun `negative nanoseconds round the time back`() {
        // -1,000,000 ns = 0xFFF0BDC0: one millisecond before 08:51:08.
        val d = parse { p -> intArrayOf(0xC0, 0xBD, 0xF0, 0xFF).forEachIndexed { i, b -> p[16 + i] = b.toByte() } }
        assertEquals(Instant.parse("2022-01-10T08:51:07.999Z").toEpochMilli(), d.utcMillis)
    }

    @Test
    fun `no fix without the fix OK flag, a 2D or 3D fix type, and valid coordinates`() {
        assertFalse(parse { it[21] = 0x00 }.hasFix)
        assertFalse(parse { it[20] = 0x00 }.hasFix)
        assertFalse(parse { it[20] = 0x05 }.hasFix)
        assertFalse(parse { it[66] = 0x01 }.hasFix)
        assertTrue(parse { it[20] = 0x02 }.hasFix)
    }

    @Test
    fun `wrong length is not a data message`() {
        assertNull(RaceBoxData.parse(ByteArray(79)))
    }
}
