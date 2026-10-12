package com.flappedear.betterdriver.transfer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataGateTest {
    private val gate = DataGate()

    @Test
    fun `a write made during a restore waits until it ends`() {
        val events = mutableListOf<String>()
        val inside = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val done = gate.exclusive {
            pool.execute {
                inside.countDown()
                gate.write { synchronized(events) { events += "write" } }
            }
            assertTrue(inside.await(2, TimeUnit.SECONDS))
            assertTrue(gate.restoring)
            Thread.sleep(SettleMillis)
            synchronized(events) { events += "restore" }
            "restored"
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals("restored", done)
        assertEquals(listOf("restore", "write"), events)
        assertFalse(gate.restoring)
    }

    @Test
    fun `a value taken before or during a restore is not written after it`() {
        val before = gate.epoch
        var during = -1L
        gate.exclusive { during = gate.epoch }
        assertFalse(gate.writeIfCurrent(before) { error("stale") })
        assertFalse(gate.writeIfCurrent(during) { error("stale") })
        var written = false
        assertTrue(gate.writeIfCurrent(gate.epoch) { written = true })
        assertTrue(written)
    }

    @Test
    fun `a restore that fails still opens the gate`() {
        runCatching { gate.exclusive { error("failed") } }
        assertFalse(gate.restoring)
        assertEquals(1, gate.write { 1 })
    }

    private companion object {
        const val SettleMillis = 100L
    }
}
