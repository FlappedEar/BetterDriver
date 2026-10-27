@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class RaceBoxDeviceTest {
    @Test
    fun `recognises models and serials from the advertised name`() {
        val micro = FoundRaceBox("RaceBox Micro 1234563241", "AA:BB:CC:DD:EE:FF", -55)
        assertTrue(micro.isMicro)
        assertEquals("RaceBox Micro", micro.model)
        assertEquals("1234563241", micro.serial)
        val mini = FoundRaceBox("RaceBox Mini S 1234567890", "AA:BB:CC:DD:EE:00", -75)
        assertFalse(mini.isMicro)
        assertEquals("RaceBox Mini S", mini.model)
        assertEquals("1234567890", mini.serial)
        assertTrue(FoundRaceBox.isRaceBox("RaceBox Mini 1234567890"))
        assertFalse(FoundRaceBox.isRaceBox("RaceBoxer 1"))
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
