@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.posix.usleep

/** The iOS recorder writes sessions the shared reader reads back, and survives being cut off (FBBD-332). */
class RecorderTest {
    private val root = NSTemporaryDirectory() + "recorder-test-" + NSUUID().UUIDString
    private val store = SessionStore(root)
    private val info = SessionInfo(1_790_000_000_000L, car = "FR-S", track = "t1", channels = GnssChannels.ALL)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun set(i: Int) = SampleSet(
        i * 40_000L,
        listOf(
            ChannelValue(GnssChannels.LAT.id, 50.0 + i * 1e-5),
            ChannelValue(GnssChannels.SPEED_KMH.id, i.toDouble()),
        ),
    )

    @Test
    fun aStoppedRecordingReadsBackWhole() {
        val rec = Recorder(store).start(info)
        var ended: String? = null
        rec.onEnded = { ended = it }
        val rpm = ChannelDef(40, "obd_rpm", "rpm", "obd", 0)
        rec.defineChannel(rpm)
        repeat(100) { rec.submit(set(it)) }
        rec.event(EventKinds.VIDEO_START, "camera=phone file=a.mov")
        assertEquals(SessionState.RECORDING, store.list().single().state)
        assertTrue(rec.stop())
        assertFalse(rec.stop())
        assertEquals("user", ended)

        val listed = store.list().single()
        assertEquals(SessionState.FINISHED, listed.state)
        assertEquals(rec.id, listed.id)
        val data = store.read(rec.id)
        assertEquals((0 until 100).map(::set), data.sampleSets)
        assertEquals(rpm, data.channels[40])
        assertEquals(rpm.provenance, store.read(rec.id).info.channels.single { it.id == 40 }.provenance)
        assertEquals("user", data.end?.reason)
        assertEquals(EventKinds.VIDEO_START, data.events.single().kind)
        assertEquals(0L, data.truncatedBytes + data.skippedBytes)
    }

    @Test
    fun chunksRotateAndEachStartsWithTheChannelTable() {
        val rec = Recorder(store, RecorderConfig(chunkMaxBytes = 2_000)).start(info)
        repeat(300) { rec.submit(set(it)) }
        rec.stop()
        val chunks = NSFileManager.defaultManager.contentsOfDirectoryAtPath(rec.dir, null)!!
            .map { it as String }.filter { it.startsWith("chunk-") }
        assertTrue(chunks.size > 5, "chunks: $chunks")
        assertEquals((0 until 300).map(::set), store.read(rec.id).sampleSets)
    }

    @Test
    fun aRecordingCutOffIsRecoveredWithEverythingSubmitted() {
        val rec = Recorder(store).start(info)
        repeat(50) { rec.submit(set(it)) }
        // As if the app were killed: files and lock dropped without an end record.
        rec.abandonForTest()
        assertEquals(SessionState.INTERRUPTED, store.list().single().state)
        val report = store.recoverInterrupted().single()
        assertEquals(50L, report.sampleSets)
        assertEquals(49 * 40_000L, report.lastSampleMicros)
        val listed = store.list().single()
        assertEquals(SessionState.RECOVERED, listed.state)
        assertEquals(49 * 40_000L, listed.durationMicros)
        val data = store.read(rec.id)
        assertEquals((0 until 50).map(::set), data.sampleSets)
        assertNull(data.end)
    }

    @Test
    fun aRecordingSessionCannotBeDeleted() {
        val rec = Recorder(store).start(info)
        assertEquals(DeleteResult.RECORDING, store.delete(rec.id))
        rec.stop()
        assertEquals(DeleteResult.DELETED, store.delete(rec.id))
        assertEquals(emptyList(), store.list())
        assertEquals(DeleteResult.NOT_FOUND, store.delete(rec.id))
    }

    @Test
    fun nothingIsWrittenAfterTheEnd() {
        val rec = Recorder(store).start(info)
        rec.submit(set(0))
        rec.stop()
        rec.submit(set(1))
        rec.event("x", "y")
        val data = store.read(rec.id)
        assertEquals(listOf(set(0)), data.sampleSets)
        assertTrue(data.events.isEmpty())
        assertEquals("user", data.end?.reason)
    }

    /** Waits up to [ms] for [done], polling every 10 ms. */
    private fun waitFor(ms: Int, done: () -> Boolean): Boolean {
        repeat(ms / 10) {
            if (done()) return true
            usleep(10_000u)
        }
        return done()
    }

    @Test
    fun dataIsSyncedWhenNoMoreArrives() {
        val rec = Recorder(store, RecorderConfig(syncIntervalMs = 50)).start(info)
        rec.submit(set(0))
        assertTrue(rec.isDirtyForTest)
        // Nothing else is submitted: the timer must sync on its own.
        assertTrue(waitFor(2_000) { !rec.isDirtyForTest })
        rec.stop()
    }

    @Test
    fun aFailedPeriodicSyncEndsTheRecordingWithAnError() {
        val rec = Recorder(store, RecorderConfig(syncIntervalMs = 50)).start(info)
        var ended: String? = null
        rec.onEnded = { ended = it }
        rec.submit(set(0))
        rec.breakFileForTest()
        assertTrue(waitFor(2_000) { !rec.isRecording })
        assertEquals("error", rec.endReason)
        assertEquals("error", ended)
        assertEquals(SessionState.INTERRUPTED, store.state(rec.id))
    }
}
