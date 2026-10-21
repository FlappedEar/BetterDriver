@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second service lookup of FBBD-272 against Arek's log of 21 Oct 2026 08:00 (1.4.1): the
 * RaceBox's services were found with status 0 but without the UART service 8,636 ms and 6,816 ms
 * after the setup began, each time with the 10 s setup timeout of a direct connection. The log of
 * 12:52 adds a lookup that never answered.
 */
class ServiceRecheckTest {
    private val setupTimeoutMs = 10_000L

    @Test
    fun `the second look at 8,636 ms gets time the 10 s setup timeout would not have left`() {
        val recheck = ServiceRecheck()
        val deadline = requireNotNull(recheck.recheck(nowMs = 8_636L, deadlineMs = setupTimeoutMs))
        // That setup's lookup started when the MTU wait ended (2,564 + 2,000 ms) and took 4,072 ms.
        val lookupMs = 8_636L - 2_564L - 2_000L
        assertTrue(8_636L + recheck.delayMs + lookupMs < deadline)
        assertTrue("the timeout alone would end it", 8_636L + recheck.delayMs + lookupMs > setupTimeoutMs)
    }

    /**
     * 21 Oct 2026 12:27 (1.5.2): setup 1 connected 4.7 s in, waited the 2 s for the MTU answer, then
     * the lookup never answered. GattLink gives a lookup 3 s, so the second look starts at 9.7 s,
     * before the 10 s setup timeout; setup 3's lookup took 950 ms.
     */
    @Test
    fun `a lookup that never answers gets its second look before the setup timeout`() {
        val recheck = ServiceRecheck()
        val overdueAt = 4_700L + 2_000L + 3_000L
        assertTrue(overdueAt < setupTimeoutMs)
        val deadline = requireNotNull(recheck.recheck(nowMs = overdueAt, deadlineMs = setupTimeoutMs))
        assertTrue(overdueAt + recheck.delayMs + 950L < deadline)
    }

    @Test
    fun `an early miss keeps the plan's deadline when that is later`() {
        val recheck = ServiceRecheck(delayMs = 500L, windowMs = 6_000L)
        assertEquals(30_000L, recheck.recheck(nowMs = 2_000L, deadlineMs = 30_000L))
    }

    @Test
    fun `one second look per connection, then the link reconnects as before`() {
        val recheck = ServiceRecheck()
        assertTrue(recheck.recheck(6_816L, setupTimeoutMs) != null)
        assertNull(recheck.recheck(13_000L, 13_316L))
        recheck.newConnection()
        assertTrue(recheck.recheck(1_000L, setupTimeoutMs) != null)
    }
}
