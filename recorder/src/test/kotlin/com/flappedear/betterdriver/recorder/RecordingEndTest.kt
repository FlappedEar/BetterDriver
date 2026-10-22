package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit A03: the recorder service ends a recording only when its writer has stopped. The writer is held
 * by a barrier in its free-space check, past the stop's wait.
 */
@Timeout(60)
class RecordingEndTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()
    private val info = SessionInfo(
        startUtcMillis = gnss.startUtcMillis,
        devices = listOf("fake-gnss"),
        channels = GnssChannels.ALL,
    )

    /** Free space that, once [hold] is set, blocks the writer until [release]. */
    private class HeldStorage : StorageProbe {
        @Volatile
        var hold = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var free = Long.MAX_VALUE

        override fun usableBytes(dir: File): Long {
            if (hold) {
                entered.countDown()
                release.await()
            }
            return free
        }
    }

    private val config = RecorderConfig(storageCheckIntervalMs = 1)

    @Test
    fun `a stop the writer does not finish in time is not an end, and the real end comes once`() {
        val store = SessionStore(tmp)
        val storage = HeldStorage()
        val rec = Recorder(store, config, storage).start(info)
        val ends = Collections.synchronizedList(mutableListOf<String>())
        val end = RecordingEnd(rec) { ends += it }
        repeat(25) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        storage.hold = true
        assertTrue(storage.entered.await(5, TimeUnit.SECONDS))

        // The writer is stuck past the wait: the host keeps the recording, its protection and its lock.
        assertFalse(end.stop("user", timeoutMs = 200))
        assertFalse(end.isEnded)
        assertTrue(ends.isEmpty())
        assertFalse(rec.isStopped)
        assertEquals(SessionState.RECORDING, store.state(rec.dir))
        // Still not ended a while later, with nothing but the stuck writer to wait for.
        Thread.sleep(300)
        assertTrue(ends.isEmpty())

        storage.release.countDown()
        assertTrue(rec.awaitStopped(5_000))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ends.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        // Both the waiting thread and the status listener see the end; the host hears it once.
        Thread.sleep(200)
        assertEquals(listOf("user"), ends.toList())
        assertTrue(end.isEnded)
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
    }

    @Test
    fun `a stop that finishes in time ends at once`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store, config).start(info)
        val ends = Collections.synchronizedList(mutableListOf<String>())
        val end = RecordingEnd(rec) { ends += it }
        assertTrue(end.stop("user"))
        assertEquals(listOf("user"), ends.toList())
        Thread.sleep(200)
        assertEquals(listOf("user"), ends.toList())
    }

    @Test
    fun `a stop the recorder makes itself ends with its own reason`() {
        val store = SessionStore(tmp)
        val storage = HeldStorage().apply { free = 0 }
        val rec = Recorder(store, config, storage).start(info)
        val ended = CountDownLatch(1)
        val ends = Collections.synchronizedList(mutableListOf<String>())
        RecordingEnd(rec) {
            ends += it
            ended.countDown()
        }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("storage_full"), ends.toList())
    }

    @Test
    fun `a user stop racing the recorder's own stop keeps the writer's reason`() {
        val store = SessionStore(tmp)
        val storage = HeldStorage()
        val rec = Recorder(store, config, storage).start(info)
        val ends = Collections.synchronizedList(mutableListOf<String>())
        val end = RecordingEnd(rec) { ends += it }
        storage.free = 0
        assertTrue(rec.awaitStopped(5_000))
        end.stop("user")
        Thread.sleep(200)
        assertEquals(listOf("storage_full"), ends.toList())
    }
}
