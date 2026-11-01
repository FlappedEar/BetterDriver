@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.io.PosixFiles
import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionFileNames
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
import kotlin.time.TimeSource
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_group_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_wait
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
        // Written, then the app is killed: files and lock dropped without an end record.
        rec.awaitWritten()
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
        rec.awaitWritten()
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

    /** FBBD-350 R2: a source hands its record over and returns; it never waits for a slow disk. */
    @Test
    fun aSlowDiskDoesNotHoldTheSource() {
        val rec = Recorder(store).start(info)
        // Each frame takes 50 ms to write, as on a slow or nearly full disk: 50 of them take 2.5 s.
        rec.writeDelayMicrosForTest = 50_000
        val begun = TimeSource.Monotonic.markNow()
        repeat(50) { rec.submit(set(it)) }
        val took = begun.elapsedNow().inWholeMilliseconds
        assertTrue(took < 200, "50 submits took $took ms with 50 ms writes")
        assertTrue(rec.queuedForTest > 0, "the writer is still behind")
        // The stop writes what the queue took, then ends.
        assertTrue(rec.stop())
        assertEquals((0 until 50).map(::set), store.read(rec.id).sampleSets)
        assertTrue(rec.writerText.startsWith("writer: max_wait_ms="), rec.writerText)
        assertTrue(rec.writerText.endsWith("dropped=0"), rec.writerText)
    }

    /** A queue the writer cannot keep up with drops records and says so, as Android's writer. */
    @Test
    fun aFullQueueDropsRecordsAndCountsThem() {
        val rec = Recorder(store, RecorderConfig(queueCapacity = 5)).start(info)
        rec.writeDelayMicrosForTest = 50_000
        repeat(100) { rec.submit(set(it)) }
        assertTrue(rec.stop())
        val data = store.read(rec.id)
        val kept = data.sampleSets
        assertTrue(kept.size in 1 until 100, "kept ${kept.size}")
        // What is kept is in order, each set whole.
        assertEquals(kept.sortedBy { it.tMicros }, kept)
        assertTrue(kept.all { it == set((it.tMicros / 40_000L).toInt()) })
        val finished = parseProperties(PosixFiles.readBytes(PosixFiles.join(rec.dir, SessionFileNames.FINISHED)))
        assertEquals((100 - kept.size).toString(), finished["droppedSamples"])
        assertEquals((100 - kept.size).toString(), data.events.last { it.kind == EventKinds.DROPPED_SAMPLES }.text)
        assertEquals(0L, data.truncatedBytes + data.skippedBytes)
    }

    /**
     * Stop during write: four sources keep submitting while the driver stops. The session ends whole:
     * an end record, no torn frame, and from each source exactly the sets before the stop, in order.
     */
    @Test
    fun aStopWhileSourcesWriteEndsTheSessionWhole() {
        val rec = Recorder(store).start(info)
        rec.writeDelayMicrosForTest = 200
        val sources = 4
        val each = 400
        val group = dispatch_group_create()
        repeat(sources) { s ->
            dispatch_group_async(group, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                repeat(each) { i ->
                    val speed = ChannelValue(GnssChannels.SPEED_KMH.id, i.toDouble())
                    rec.submit(SampleSet(s * 1_000_000_000L + i, listOf(speed)))
                    usleep(500u)
                }
            }
        }
        usleep(80_000u)
        assertTrue(rec.stop())
        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)
        val data = store.read(rec.id)
        assertEquals("user", data.end?.reason)
        assertEquals(0L, data.truncatedBytes + data.skippedBytes)
        assertEquals(SessionState.FINISHED, store.state(rec.id))
        val written = data.sampleSets.groupBy { (it.tMicros / 1_000_000_000L).toInt() }
        repeat(sources) { s ->
            val got = written[s].orEmpty().map { (it.tMicros % 1_000_000_000L).toInt() }
            assertEquals((0 until got.size).toList(), got, "source $s: an unbroken run from its first set")
            assertTrue(got.size < each, "source $s was still submitting at the stop")
        }
        assertTrue(data.sampleSets.isNotEmpty())
    }

    /**
     * Kill during write: the app dies with the writer behind. What is on disk is recovered: a whole,
     * ordered run from the first set, never a torn or reordered one; what waited in the queue is lost.
     */
    @Test
    fun aKillWithTheWriterBehindRecoversAnUnbrokenRun() {
        val rec = Recorder(store).start(info)
        rec.writeDelayMicrosForTest = 5_000
        repeat(200) { rec.submit(set(it)) }
        usleep(100_000u)
        rec.abandonForTest()
        val report = store.recoverInterrupted().single()
        val data = store.read(rec.id)
        val n = data.sampleSets.size
        assertTrue(n in 1 until 200, "recovered $n")
        assertEquals((0 until n).map(::set), data.sampleSets)
        assertEquals(n.toLong(), report.sampleSets)
        assertNull(data.end)
    }
}
