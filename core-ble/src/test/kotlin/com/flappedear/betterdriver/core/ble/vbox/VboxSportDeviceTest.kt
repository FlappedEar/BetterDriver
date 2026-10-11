package com.flappedear.betterdriver.core.ble.vbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VboxSportDeviceTest {
    @Test
    fun `matches the paired name RACELOGIC shows`() {
        assertTrue(isVboxSport("VBSport 00000300", leOnly = false))
        assertTrue(isVboxSport("vbsport 123", leOnly = false))
    }

    @Test
    fun `rejects other devices, unnamed ones and LE-only ones`() {
        assertFalse(isVboxSport("RaceBox Mini 1234567890", leOnly = false))
        assertFalse(isVboxSport("Qstarz 818XT", leOnly = false))
        assertFalse(isVboxSport(null, leOnly = false))
        assertFalse(isVboxSport("VBSport 00000300", leOnly = true))
    }
}
