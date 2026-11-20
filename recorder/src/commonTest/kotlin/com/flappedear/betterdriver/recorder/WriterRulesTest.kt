package com.flappedear.betterdriver.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriterRulesTest {
    private val config =
        RecorderConfig(storageCheckIntervalMs = 2_000, storageWarnBytes = 1_000, storageStopBytes = 100)

    @Test
    fun theDefaultsAreTheOnesBothRecordersHadBeforeTheyWereShared() {
        val c = RecorderConfig()
        assertEquals(500L, c.syncIntervalMs)
        assertEquals(100L, c.flushIntervalMs)
        assertEquals(8L shl 20, c.chunkMaxBytes)
        assertEquals(60_000L, c.chunkMaxAgeMs)
        assertEquals(200L shl 20, c.storageWarnBytes)
        assertEquals(50L shl 20, c.storageStopBytes)
        assertEquals(2_000L, c.storageCheckIntervalMs)
        assertEquals(100_000, c.queueCapacity)
        assertEquals(1_024, c.listenerQueueCapacity)
    }

    @Test
    fun aShortSyncIntervalBringsTheFlushDownWithIt() {
        assertEquals(50L, RecorderConfig(syncIntervalMs = 50).flushIntervalMs)
        assertFailsWith<IllegalArgumentException> { RecorderConfig(syncIntervalMs = 50, flushIntervalMs = 100) }
        assertFailsWith<IllegalArgumentException> { RecorderConfig(syncIntervalMs = 1_001) }
        assertFailsWith<IllegalArgumentException> { RecorderConfig(queueCapacity = 0) }
    }

    @Test
    fun aChunkRotatesWhenBigOrOldEnough() {
        val c = RecorderConfig(chunkMaxBytes = 4_096, chunkMaxAgeMs = 60_000)
        assertFalse(c.chunkDue(4_095, 59_999))
        assertTrue(c.chunkDue(4_096, 0))
        assertTrue(c.chunkDue(0, 60_000))
    }

    @Test
    fun freeSpaceIsReadOnlyWhenACheckIsDue() {
        val guard = StorageGuard(config)
        var reads = 0
        val free = { reads++; 5_000L }
        assertEquals(StorageCheck(5_000, warn = false, stop = false), guard.check(0, free))
        assertNull(guard.check(1_999, free))
        assertEquals(1, reads)
        assertEquals(StorageCheck(5_000, warn = false, stop = false), guard.check(2_000, free))
        assertEquals(2, reads)
    }

    @Test
    fun lowSpaceWarnsOnceAndTooLittleStops() {
        val guard = StorageGuard(config)
        assertEquals(StorageCheck(999, warn = true, stop = false), guard.check(0) { 999 })
        assertEquals(StorageCheck(500, warn = false, stop = false), guard.check(2_000) { 500 })
        assertEquals(StorageCheck(99, warn = false, stop = true), guard.check(4_000) { 99 })
    }

    @Test
    fun aDiskAlreadyFullWarnsAndStopsAtTheFirstCheck() {
        assertEquals(StorageCheck(10, warn = true, stop = true), StorageGuard(config).check(0) { 10 })
    }

    @Test
    fun dropsAreReportedOnlyWhenTheCountChanges() {
        val drops = DropReport()
        assertNull(drops.due(0))
        assertEquals(3L, drops.due(3))
        assertNull(drops.due(3))
        assertEquals(4L, drops.due(4))
    }
}
