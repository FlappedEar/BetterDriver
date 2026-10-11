@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBoxDeviceTest {
    @Test
    fun `recognises models and serials from the advertised name`() {
        val micro = FoundRaceBox("RaceBox Micro 3241", "AA:BB:CC:DD:EE:FF", -55)
        assertTrue(micro.isMicro)
        assertEquals("RaceBox Micro", micro.model)
        assertEquals("3241", micro.serial)
        val mini = FoundRaceBox("RaceBox Mini S 1234567", "AA:BB:CC:DD:EE:00", -75)
        assertFalse(mini.isMicro)
        assertEquals("RaceBox Mini S", mini.model)
        assertEquals("1234567", mini.serial)
        assertTrue(FoundRaceBox.isRaceBox("RaceBox Mini 1"))
        assertFalse(FoundRaceBox.isRaceBox("Pixel Buds"))
        assertFalse(FoundRaceBox.isRaceBox(null))
    }

    @Test
    fun `signal bars`() {
        val bars = listOf(-50, -65, -75, -85, -95).map { FoundRaceBox("RaceBox Micro 1", "x", it).bars }
        assertEquals(listOf(4, 3, 2, 1, 0), bars)
    }

    @Test
    fun `reconnect backs off to a cap and resets`() {
        val p = ReconnectPolicy(firstDelayMs = 500, maxDelayMs = 3_000)
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 3_000L), List(5) { p.nextDelayMs() })
        assertEquals(5, p.attempts)
        p.reset()
        assertEquals(500L, p.nextDelayMs())
    }
}
