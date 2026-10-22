package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Audit A15: a writer that is blocked (a fake with stuck I/O) while samples keep coming, past the
 * budget and past the wait. The copies waiting for it, measured by the bytes they really hold, stay
 * within the budget, and the backlog ends in a stalled state that refuses at once.
 */
class WriterBacklogTest {
    private val budgetKib = 1_024
    private val waitMs = 50L

    @Test
    fun blockedWriterKeepsWaitingCopiesWithinTheBudget() {
        val backlog = WriterBacklog(budgetKib, waitMs)
        val writer = Executors.newSingleThreadExecutor()
        val stuck = CountDownLatch(1)
        val waitingBytes = AtomicLong()
        var maxWaiting = 0L
        var queued = 0
        var refused = 0
        var fullWaits = 0
        try {
            // 4K-sized video copies and small sound copies, as the encoder makes them.
            repeat(200) { i ->
                val copy = ByteBuffer.allocateDirect(if (i % 3 == 0) 4 * 1_024 else 256 * 1_024)
                if (backlog.reserve(copy.capacity()) { fullWaits++ }) {
                    queued++
                    maxWaiting = maxOf(maxWaiting, waitingBytes.addAndGet(copy.capacity().toLong()))
                    writer.execute {
                        stuck.await()
                        waitingBytes.addAndGet(-copy.capacity().toLong())
                        backlog.release(copy.capacity())
                    }
                } else {
                    refused++
                }
            }
            assertTrue("waiting copies hold $maxWaiting bytes", maxWaiting <= budgetKib * 1_024L)
            assertTrue("refused only when the next copy did not fit", maxWaiting > (budgetKib - 256) * 1_024L)
            assertTrue("stalled after one wait", backlog.stalled)
            assertEquals("only one wait: later samples are refused at once", 1, fullWaits)
            assertEquals(200, queued + refused)

            // The writer comes back: everything it held is released, and the backlog stays stalled.
            stuck.countDown()
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(0, backlog.takenKib)
            assertFalse("a stalled writer gets nothing more", backlog.reserve(1))
        } finally {
            stuck.countDown()
            writer.shutdownNow()
        }
    }

    @Test
    fun refusalComesAfterTheWaitNotBefore() {
        val backlog = WriterBacklog(budgetKib, waitMs)
        assertTrue(backlog.reserve(budgetKib * 1_024))
        val begin = System.nanoTime()
        assertFalse(backlog.reserve(1_024))
        val waitedMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs >= waitMs)

        val again = System.nanoTime()
        assertFalse(backlog.reserve(1_024))
        assertTrue("no second wait", (System.nanoTime() - again) / 1_000_000 < waitMs)
    }

    @Test
    fun slowWriterThatCatchesUpWithinTheWaitIsNotStalled() {
        val backlog = WriterBacklog(budgetKib, waitMs = 2_000)
        val writer = Executors.newSingleThreadExecutor()
        try {
            repeat(50) {
                val capacity = 256 * 1_024
                assertTrue(backlog.reserve(capacity))
                writer.execute {
                    Thread.sleep(5) // slow I/O, but it keeps up
                    backlog.release(capacity)
                }
            }
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(backlog.stalled)
            assertEquals(0, backlog.takenKib)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test
    fun stallIsReportedOnceAndRoomFreedAfterItIsNotTaken() {
        val backlog = WriterBacklog(budgetKib, waitMs)
        assertFalse("no stall yet", backlog.claimStallReport())
        assertTrue(backlog.reserve(budgetKib * 1_024))
        assertFalse(backlog.reserve(1_024))
        // The writer frees its room after the stall: the video or sound thread still queues nothing.
        backlog.release(budgetKib * 1_024)
        assertFalse(backlog.reserve(1_024))
        assertEquals(0, backlog.takenKib)

        // Both encoders' threads find the stall at once: only one reports it.
        val pool = Executors.newFixedThreadPool(8)
        try {
            val claims = (1..8).map { pool.submit<Boolean> { backlog.claimStallReport() } }.map { it.get() }
            assertEquals(1, claims.count { it })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun copyBiggerThanTheBudgetTakesAllOfIt() {
        val backlog = WriterBacklog(budgetKib, waitMs)
        assertTrue(backlog.reserve(budgetKib * 4 * 1_024))
        assertEquals(budgetKib, backlog.takenKib)
        backlog.release(budgetKib * 4 * 1_024)
        assertEquals(0, backlog.takenKib)
    }
}
