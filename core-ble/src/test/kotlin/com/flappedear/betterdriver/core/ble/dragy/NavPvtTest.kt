@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.dragy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPvtTest {
    @Test
    fun `parses every decoded field`() {
        val p = NavPvt.parse(navPvtPayload())!!
        assertEquals(123_456_000L, p.iTowMs)
        assertEquals(Instant.parse("2026-10-11T10:11:12.200Z").toEpochMilli(), p.utcMillis)
        assertEquals(3, p.fixType)
        assertTrue(p.fixOk)
        assertEquals(14, p.satellites)
        assertEquals(50.2187, p.latDeg, 1e-9)
        assertEquals(19.0428, p.lonDeg, 1e-9)
        assertEquals(290.5, p.ellipsoidAltM, 1e-9)
        assertEquals(250.5, p.mslAltM, 1e-9)
        assertEquals(1.5, p.horizontalAccM, 1e-9)
        assertEquals(2.5, p.verticalAccM, 1e-9)
        assertEquals(37.0368, p.speedKmh, 1e-9)
        assertEquals(90.5, p.headingDeg, 1e-9)
        assertEquals(0.3, p.speedAccMps, 1e-9)
        assertEquals(1.5, p.headingAccDeg, 1e-9)
        assertEquals(1.35, p.pdop, 1e-9)
        assertTrue(p.hasFix)
    }

    @Test
    fun `negative coordinates and a negative nano`() {
        val p = NavPvt.parse(navPvtPayload(lat = -338_688_000, lon = -1_512_093_000, nano = -1_000_000))!!
        assertEquals(-33.8688, p.latDeg, 1e-9)
        assertEquals(-151.2093, p.lonDeg, 1e-9)
        assertEquals(Instant.parse("2026-10-11T10:11:11.999Z").toEpochMilli(), p.utcMillis)
    }

    @Test
    fun `no UTC unless date and time are valid`() {
        assertNull(NavPvt.parse(navPvtPayload(valid = 0x01))!!.utcMillis)
        assertNull(NavPvt.parse(navPvtPayload(valid = 0x02))!!.utcMillis)
    }

    @Test
    fun `fix needs gnssFixOK and a 3D or GNSS plus dead reckoning fix`() {
        assertTrue(NavPvt.parse(navPvtPayload(fixType = 4))!!.hasFix)
        assertFalse(NavPvt.parse(navPvtPayload(fixType = 2))!!.hasFix)
        assertFalse(NavPvt.parse(navPvtPayload(fixType = 0))!!.hasFix)
        assertFalse(NavPvt.parse(navPvtPayload(flags = 0))!!.hasFix)
    }

    @Test
    fun `wrong length is rejected`() {
        assertNull(NavPvt.parse(ByteArray(84)))
        assertNull(NavPvt.parse(ByteArray(80)))
    }

    @Test
    fun `full frame is 100 bytes as dragy-dash observed`() {
        assertEquals(100, navPvtPacket().size)
        assertEquals(listOf(0xB5, 0x62, 0x01, 0x07, 0x5C, 0x00), navPvtPacket().take(6).map { it.toInt() and 0xFF })
    }
}
