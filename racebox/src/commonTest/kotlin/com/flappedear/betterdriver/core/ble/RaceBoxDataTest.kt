@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class RaceBoxDataTest {
    private fun parse(edit: (ByteArray) -> Unit = {}) =
        checkNotNull(RaceBoxData.parse(RaceBoxTestData.payload().also(edit)))

    @Test
    fun `decodes every field of RaceBox's documented example`() {
        val d = parse()
        assertEquals(1641804668239L /* 2022-01-10T08:51:08.239Z */, d.utcMillis)
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
        assertEquals(0x59, d.batteryStatus) // Mini: 89 %, not charging
        assertEquals(-0.003, d.accelXg, 1e-9)
        assertEquals(0.113, d.accelYg, 1e-9)
        assertEquals(0.974, d.accelZg, 1e-9)
        // The document prints these without signs (2.09, 0.86, 0.04); the fields are Int16, so FF2F is -209.
        assertEquals(-2.09, d.gyroXdps, 1e-9)
        assertEquals(0.86, d.gyroYdps, 1e-9)
        assertEquals(-0.04, d.gyroZdps, 1e-9)
    }

    @Test
    fun `Micro input voltage is the byte divided by 10`() {
        assertEquals(12.1, parse { it[67] = 0x79 }.microInputVolts, 1e-9)
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
        assertEquals(1641804667999L /* 2022-01-10T08:51:07.999Z */, d.utcMillis)
    }

    @Test
    fun `a fix needs the fix OK flag a 3D fix and valid coordinates as RaceBox recommends`() {
        assertTrue(parse().hasFix)
        assertFalse(parse { it[21] = 0x00 }.hasFix)
        assertFalse(parse { it[20] = 0x00 }.hasFix)
        assertFalse(parse { it[20] = 0x02 }.hasFix)
        assertFalse(parse { it[66] = 0x01 }.hasFix)
    }

    @Test
    fun `too short is not a data message`() {
        assertNull(RaceBoxData.parse(ByteArray(79)))
    }

    @Test
    fun `bytes past the documented 80 are ignored`() {
        val data = checkNotNull(RaceBoxData.parse(RaceBoxTestData.payload() + ByteArray(8)))
        assertEquals(checkNotNull(RaceBoxData.parse(RaceBoxTestData.payload())), data)
    }
}
