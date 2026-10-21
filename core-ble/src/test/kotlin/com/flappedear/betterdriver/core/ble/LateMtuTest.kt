@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The MTU asked for again once a link set up without it streams, and the Bluetooth LE OBD adapter
 * waiting for the RaceBox to connect first (FBBD-282). The ladder replays Arek's drive of 21 Oct 2026
 * 12:27: setup 1 (MTU asked for) had no MTU answer and no service answer, setup 2 (MTU asked for)
 * ended with status 147, setup 3 (no MTU request) streamed and stayed at the default MTU.
 */
class LateMtuTest {
    /** [GattLink]'s watchdog ticks once a second while data flows; the times it would ask. */
    private fun ticks(late: LateMtu, fromMs: Long, toMs: Long) =
        (fromMs..toMs step 1_000L).filter { late.ask(it) }

    @Test
    fun `the 12_27 ladder streams at setup 3 without MTU, then asks for it 5 s later, once`() {
        val ladder = SetupLadder()
        val late = LateMtu()
        // Setups 1 and 2: the MTU was asked for during the setup; nothing to ask later.
        repeat(2) {
            val plan = ladder.plan()
            assertTrue(plan.askMtu)
            late.newConnection(plan.askMtu)
            assertEquals(emptyList<Long>(), ticks(late, 0, 20_000))
            late.closed()
            ladder.failed()
        }
        // Setup 3: no MTU request; data from 12:27:32.2, 16 s after Start.
        val plan = ladder.plan()
        assertFalse(plan.askMtu)
        late.newConnection(plan.askMtu)
        assertEquals(listOf(21_000L), ticks(late, 16_000, 60_000))
        assertTrue(late.waiting)
        late.answered()
        assertFalse(late.waiting)
        assertEquals(emptyList<Long>(), ticks(late, 61_000, 120_000))
        // A drop later: the next connection without the MTU request asks again.
        late.closed()
        late.newConnection(askedInSetup = false)
        assertEquals(listOf(205_000L), ticks(late, 200_000, 230_000))
    }

    @Test
    fun `Android busy with another request: asked again on the next ticks, at most 5 times`() {
        val late = LateMtu()
        late.newConnection(askedInSetup = false)
        val asked = mutableListOf<Long>()
        for (t in 0L..30_000L step 1_000L) {
            if (late.ask(t)) {
                asked += t
                late.notSent()
            }
        }
        assertEquals(listOf(5_000L, 6_000L, 7_000L, 8_000L, 9_000L), asked)
    }

    @Test
    fun `a late request unanswered before a drop is never made again on the link`() {
        // The 20 Oct stall: a RaceBox that does not answer the MTU request and is dropped with 147.
        val late = LateMtu()
        late.newConnection(askedInSetup = false)
        assertEquals(listOf(5_000L), ticks(late, 0, 10_000))
        late.closed()
        assertTrue(late.givenUp)
        late.newConnection(askedInSetup = false)
        assertEquals(emptyList<Long>(), ticks(late, 20_000, 60_000))
    }

    @Test
    fun `the OBD adapter waits for the RaceBox to stream, at most the limit`() {
        val setups = BleSetups()
        val raceBox = "E7:D2:76:DC:ED:23"
        val vgate = "C0:08:00:00:00:D4"
        assertEquals(emptySet<String>(), setups.awaitQuiet(vgate, 1_000))
        setups.started(raceBox)
        // Its own setup does not count.
        setups.started(vgate)
        val streams = thread {
            Thread.sleep(200)
            setups.ended(raceBox)
        }
        val began = System.nanoTime()
        assertEquals(emptySet<String>(), setups.awaitQuiet(vgate, 5_000))
        val waited = (System.nanoTime() - began) / 1_000_000
        streams.join()
        assertTrue("waited $waited ms", waited in 150..3_000)
        // A RaceBox that never streams holds the adapter up only for the limit.
        setups.started(raceBox)
        val t0 = System.nanoTime()
        assertEquals(setOf(raceBox), setups.awaitQuiet(vgate, 300))
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 290)
    }
}
