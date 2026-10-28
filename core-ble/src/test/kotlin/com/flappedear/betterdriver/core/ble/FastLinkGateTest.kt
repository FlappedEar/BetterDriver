@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast link asked for again only when the link is slower than asked for (FBBD-305). Replays the
 * RaceBox link of Arek's FR-S drive of 28 Oct 2026 20:04: streaming at 15 ms on 2M after the first
 * request, then 10 "messages missing" re-requests, three of them followed by a 640-760 ms gap.
 */
class FastLinkGateTest {
    @Test
    fun `nothing known yet - asks, as before`() {
        assertTrue(FastLinkGate().worthAsking())
    }

    @Test
    fun `the 20_04 link at 15 ms on 2M - not asked again`() {
        val gate = FastLinkGate()
        gate.newConnection()
        gate.interval(15.0)
        gate.phy(tx2m = true, rx2m = true)
        assertFalse(gate.worthAsking())
    }

    @Test
    fun `Android relaxed the interval - asked again (FBBD-218)`() {
        val gate = FastLinkGate()
        gate.interval(15.0)
        gate.phy(tx2m = true, rx2m = true)
        gate.interval(48.75)
        assertTrue(gate.worthAsking())
    }

    @Test
    fun `fast interval but 1M PHY or PHY never reported - asked again`() {
        val gate = FastLinkGate()
        gate.interval(11.25)
        assertTrue(gate.worthAsking())
        gate.phy(tx2m = true, rx2m = false)
        assertTrue(gate.worthAsking())
    }

    @Test
    fun `a new connection forgets the old one`() {
        val gate = FastLinkGate()
        gate.interval(15.0)
        gate.phy(tx2m = true, rx2m = true)
        gate.newConnection()
        assertTrue(gate.worthAsking())
    }
}
