@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The RaceBox diagnostics probe, shared by Android and iOS (FBBD-89, moved to common in FBBD-371). */
class RaceBoxDiagnosticsTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** The example packet with its iTOW replaced. */
    private fun at(itow: Long) = RaceBoxTestData.microPacket {
        for (i in 0 until 4) it[i] = (itow shr (8 * i)).toByte()
    }

    @Test
    fun `queries match the document's request packets`() {
        // p. 11: "B5 62 FF 22 00 00 21 62".
        assertContentEquals(hex("B5 62 FF 22 00 00 21 62"), RaceBoxMessages.recordingStatusQuery())
        assertContentEquals(ubxPacket(0xFF, 0x27, ByteArray(0)), RaceBoxMessages.gnssConfigQuery())
    }

    @Test
    fun `parses the document's recording status example`() {
        // p. 11–12: not recording, 34 %, security enabled and locked, 67173 of 196608 messages.
        val diag = RaceBoxDiagnostics(isMicro = true)
        diag.feed(hex("B5 62 FF 22 0C 00 00 22 01 00 65 06 01 00 00 00 03 00 BF 74"), 0)
        val m = diag.snapshot(0).memory!!
        assertFalse(m.recording)
        assertEquals(34, m.levelPercent)
        assertTrue(m.securityEnabled)
        assertFalse(m.unlocked)
        assertEquals(67_173L, m.storedMessages)
        assertEquals(196_608L, m.capacityMessages)
    }

    @Test
    fun `parses a GNSS config reply`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        diag.feed(ubxPacket(0xFF, 0x27, byteArrayOf(4, 0, 3)), 0)
        val c = diag.snapshot(0).gnssConfig!!
        assertEquals("Automotive", c.platformName)
        assertFalse(c.speed3d)
        assertEquals(3, c.minHorizontalAccuracyM)
    }

    @Test
    fun `notes a NACK`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        diag.feed(ubxPacket(0xFF, 0x03, byteArrayOf(0xFF.toByte(), 0x27)), 0)
        assertTrue(diag.snapshot(0).nackSeen)
    }

    @Test
    fun `power is volts on a Micro and battery on a Mini`() {
        assertEquals(RaceBoxPower.Input(12.1), RaceBoxPower.of(0x79, isMicro = true))
        // p. 8: 0x59 = 89 %, not charging.
        assertEquals(RaceBoxPower.Battery(89, false), RaceBoxPower.of(0x59, isMicro = false))
        assertEquals(RaceBoxPower.Battery(50, true), RaceBoxPower.of(0x80 or 50, isMicro = false))
    }

    @Test
    fun `rate counts the last second`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        for (i in 0 until 50) diag.feed(at(1_000L + i * 40), i * 40L)
        val s = diag.snapshot(1_990)
        assertEquals(50L, s.messages)
        assertEquals(25, s.rateHz)
        assertEquals(0L, s.missed)
        assertEquals(12.1, (s.power as RaceBoxPower.Input).volts, 1e-9)
    }

    @Test
    fun `counts messages missing from the 25 Hz stream`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        var t = 1_000L
        repeat(10) { diag.feed(at(t), t); t += 40 }
        t += 80 // two messages lost
        repeat(5) { diag.feed(at(t), t); t += 40 }
        assertEquals(2L, diag.snapshot(t).missed)
    }

    @Test
    fun `a lower recording rate is not counted as loss`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        var t = 1_000L
        repeat(20) { diag.feed(at(t), t); t += 100 }
        assertEquals(0L, diag.snapshot(t).missed)
    }

    @Test
    fun `counts bad checksums`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        val bad = RaceBoxTestData.microPacket().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        diag.feed(bad, 0)
        val s = diag.snapshot(0)
        assertEquals(1L, s.checksumErrors)
        assertNull(s.latest)
    }

    @Test
    fun `firmware 3_3 and later supports the GNSS config query`() {
        assertFalse(RaceBoxDeviceInfo(firmware = "2.6").supportsGnssConfig)
        assertFalse(RaceBoxDeviceInfo(firmware = "3.2").supportsGnssConfig)
        assertTrue(RaceBoxDeviceInfo(firmware = "3.3").supportsGnssConfig)
        assertTrue(RaceBoxDeviceInfo(firmware = "4.0").supportsGnssConfig)
        assertFalse(RaceBoxDeviceInfo().supportsGnssConfig)
    }

    @Test
    fun `the Model string decides how power is read`() {
        val diag = RaceBoxDiagnostics(isMicro = true)
        diag.feed(RaceBoxTestData.packet(), 0)
        assertTrue(diag.snapshot(0).power is RaceBoxPower.Input)
        diag.deviceInfo { it.copy(model = "RaceBox Mini") }
        assertEquals(RaceBoxPower.Battery(89, false), diag.snapshot(0).power)
    }
}
