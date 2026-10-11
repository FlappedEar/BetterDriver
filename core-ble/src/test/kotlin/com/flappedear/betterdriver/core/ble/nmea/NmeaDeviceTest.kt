@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaDeviceTest {
    @Test
    fun `receivers are offered, RaceBoxes, GoPros and obvious non-receivers are not`() {
        // Uncategorised (0x1F00) and miscellaneous (0x0000) are what GNSS receivers usually report.
        assertTrue(isNmeaCandidate("Qstarz 818XT", 0x1F00, leOnly = false))
        assertTrue(isNmeaCandidate("Garmin GLO #4a1b2", 0x0000, leOnly = false))
        assertTrue(isNmeaCandidate("XGPS160-ABCDE", null, leOnly = false))
        assertTrue(isNmeaCandidate(null, null, leOnly = false))
        assertFalse(isNmeaCandidate("RaceBox Micro 1234563241", null, leOnly = false))
        assertFalse(isNmeaCandidate("GoPro 1234", null, leOnly = false))
        assertFalse(isNmeaCandidate("Qstarz BLE", null, leOnly = true))
        assertFalse(isNmeaCandidate("Pixel Buds", 0x0400, leOnly = false))
        assertFalse(isNmeaCandidate("Pixel 8", 0x0200, leOnly = false))
        assertFalse(isNmeaCandidate("ThinkPad", 0x0100, leOnly = false))
    }
}
