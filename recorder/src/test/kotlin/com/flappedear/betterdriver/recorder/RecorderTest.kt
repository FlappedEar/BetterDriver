package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecorderTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()
    private val obdLike = ChannelDef(100, "water temp", "C", "test", 1)

    private fun info(channels: List<ChannelDef> = GnssChannels.ALL) = SessionInfo(
        startUtcMillis = gnss.startUtcMillis,
        car = "Corolla",
        track = "Fake ring",
        devices = listOf("fake-gnss"),
        startFinish = gnss.startFinish,
        channels = channels,
    )

    /** Feeds [n] fixes with fixed timestamps, so two runs produce identical sessions. */
    private fun feed(rec: Recording, n: Int) {
        rec.defineChannel(obdLike)
        for (i in 0 until n) {
            val t = i * 40_000L
            rec.submit(SampleSet(t, gnss.fix(i.toLong())))
            if (i % 5 == 0) rec.submit(SampleSet(t + 1, listOf(ChannelValue(obdLike.id, 80.0 + i / 5))))
        }
    }

    private fun exportVbo(store: SessionStore, id: String): String {
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(id), out)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `metadata is on disk before the first sample`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val meta = SessionFiles.readProps(File(rec.dir, SessionFiles.META))!!
        assertEquals("Corolla", meta.getProperty("car"))
        assertEquals("Fake ring", meta.getProperty("track"))
        assertEquals(SessionState.RECORDING, store.state(rec.dir))
        rec.stop()
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
    }

    @Test
    fun `metadata keeps non-Latin text`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(
            info().copy(car = "Scion FR-S «86»", track = "Tor Łódź 東京", devices = listOf("RaceBox ü")),
        )
        rec.stop()
        val read = store.read(rec.id).info
        assertEquals("Scion FR-S «86»", read.car)
        assertEquals("Tor Łódź 東京", read.track)
        assertEquals(listOf("RaceBox ü"), read.devices)
    }

    @Test
    fun `a clean recording reads back every sample`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        feed(rec, 500)
        rec.stop()
        val data = store.read(rec.id)
        assertEquals(SessionState.FINISHED, data.state)
        assertEquals("user", data.end?.reason)
        val fixes = data.sampleSets.filter { s -> s.values.any { it.channelId == GnssChannels.LAT.id } }
        assertEquals(500, fixes.size)
        fixes.forEachIndexed { i, s -> assertEquals(gnss.fix(i.toLong()), s.values) }
        assertEquals(obdLike, data.channels[obdLike.id])
        assertEquals(0, rec.droppedCount)
    }

    @Test
    fun `samples reach the disk within the sync interval without a stop`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store, RecorderConfig(syncIntervalMs = 200)).start(info())
        feed(rec, 50)
        Thread.sleep(700)
        // Read the files as another process would: only what was written out is visible.
        val data = store.read(rec.dir)
        assertEquals(50, data.sampleSets.count { s -> s.values.any { it.channelId == GnssChannels.LAT.id } })
        rec.stop()
    }

    @Test
    fun `chunks rotate and every chunk can be read on its own`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store, RecorderConfig(chunkMaxBytes = 4_096)).start(info())
        feed(rec, 300)
        rec.stop()
        val chunks = SessionFiles.chunks(rec.dir)
        assertTrue(chunks.size > 5, "expected several chunks, got ${chunks.size}")
        // Delete all but the last chunk: its channel table still names the GNSS channels.
        chunks.dropLast(1).forEach { it.delete() }
        val data = store.read(rec.dir)
        assertEquals(GnssChannels.LAT, data.channels[GnssChannels.LAT.id])
        assertEquals(obdLike, data.channels[obdLike.id])
    }

    @Test
    fun `a throwing live consumer does not change the recording`() {
        val clean = SessionStore(File(tmp, "clean"))
        val a = Recorder(clean).start(info())
        feed(a, 400)
        a.stop()

        val crashing = SessionStore(File(tmp, "crashing"))
        val b = Recorder(crashing).start(info())
        val calls = AtomicLong()
        b.listeners.add(object : RecorderListener {
            override fun onSamples(set: SampleSet) {
                calls.incrementAndGet()
                error("coach crashed")
            }
        })
        b.listeners.add(object : RecorderListener {
            override fun onSamples(set: SampleSet) {
                Thread.sleep(5) // a stalled consumer must not hold up writing either
            }
        })
        feed(b, 400)
        // Listener errors are logged asynchronously; give the consumer time to fail at least once.
        val until = System.nanoTime() + 5_000_000_000L
        while (calls.get() < 10 && System.nanoTime() < until) Thread.sleep(10)
        b.stop()

        assertTrue(calls.get() > 0)
        val va = exportVbo(clean, a.id).substringAfter("[column names]")
        val vb = exportVbo(crashing, b.id).substringAfter("[column names]")
        assertEquals(va, vb)
        assertTrue(crashing.read(b.id).events.any { it.kind == EventKinds.LISTENER_ERROR })
    }

    @Test
    fun `a listener failing on everything cannot flood the session`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val calls = AtomicLong()
        rec.listeners.add(object : RecorderListener {
            override fun onSamples(set: SampleSet) = fail()
            override fun onEvent(event: RecorderEvent) = fail()
            override fun onChannel(def: ChannelDef) = fail()
            private fun fail() {
                calls.incrementAndGet()
                error("broken")
            }
        })
        rec.event("marker", "x")
        feed(rec, 200)
        Thread.sleep(1_500)
        rec.stop()
        val errors = store.read(rec.id).events.filter { it.kind == EventKinds.LISTENER_ERROR }
        assertTrue(calls.get() > 100, "listener called ${calls.get()} times")
        assertTrue(errors.size in 1..3, "expected at most one error per second, got ${errors.size}")
    }

    @Test
    fun `storage stop happens even when the queue never empties`() {
        val store = SessionStore(tmp)
        val free = AtomicLong(1L shl 40)
        val rec = Recorder(
            store,
            RecorderConfig(storageCheckIntervalMs = 10, storageWarnBytes = 1_000, storageStopBytes = 100),
            storage = { free.get() },
        ).start(info())
        val producer = Thread {
            var i = 0L
            while (!rec.isStopped) rec.submit(SampleSet(i * 40_000, gnss.fix(i++)))
        }.apply { start() }
        Thread.sleep(200)
        free.set(50)
        assertTrue(rec.awaitStopped(5_000), "recorder did not stop on low storage")
        producer.join(1_000)
        assertEquals("storage_full", rec.finalStatus?.reason)
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
    }

    @Test
    fun `a backlog bigger than the space left is counted as dropped, not written`() {
        val store = SessionStore(tmp)
        val low = java.util.concurrent.atomic.AtomicBoolean(false)
        val probing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rec = Recorder(
            store,
            RecorderConfig(storageCheckIntervalMs = 1, storageWarnBytes = 1_000, storageStopBytes = 100),
            storage = {
                if (low.get()) {
                    probing.countDown()
                    release.await()
                    90L
                } else {
                    1L shl 40
                }
            },
        ).start(info())
        low.set(true)
        // The writer is now held inside the storage check; build a backlog behind it.
        assertTrue(probing.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repeat(1_000) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        release.countDown()
        assertTrue(rec.awaitStopped(5_000))
        assertEquals("storage_full", rec.finalStatus?.reason)
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
        // 90 bytes free, 50 kept back for the end record: 40 bytes left, less than one GNSS frame.
        assertEquals(1_000, rec.droppedCount)
        val finished = SessionFiles.readProps(File(rec.dir, SessionFiles.FINISHED))!!
        assertEquals("1000", finished.getProperty("droppedSamples"))
    }

    @Test
    fun `finishing on low storage starts no new chunk`() {
        val store = SessionStore(tmp)
        val low = java.util.concurrent.atomic.AtomicBoolean(false)
        val probing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rec = Recorder(
            store,
            // Every write would normally rotate, adding a channel table the drain budget does not cover.
            RecorderConfig(
                chunkMaxBytes = 1,
                storageCheckIntervalMs = 1,
                storageWarnBytes = 30_000,
                storageStopBytes = 20_000,
            ),
            storage = {
                if (low.get()) {
                    probing.countDown()
                    release.await()
                    15_000L
                } else {
                    1L shl 40
                }
            },
        ).start(info())
        low.set(true)
        assertTrue(probing.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val chunksBefore = SessionFiles.chunks(rec.dir).size
        repeat(1_000) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        release.countDown()
        assertTrue(rec.awaitStopped(5_000))
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
        assertEquals(chunksBefore, SessionFiles.chunks(rec.dir).size)
        // 15,000 free minus 10,000 kept back: a 5,000-byte budget holds some of the backlog, not all of it.
        assertTrue(rec.droppedCount in 1L..999L, "dropped ${rec.droppedCount}")
    }

    @Test
    fun `changing the session info after start changes neither metadata nor chunks`() {
        val store = SessionStore(tmp)
        val channels = GnssChannels.ALL.toMutableList()
        val rec = Recorder(store).start(info(channels))
        channels.add(obdLike)
        rec.stop()
        val data = store.read(rec.id)
        assertEquals(GnssChannels.ALL, data.info.channels)
        assertEquals(GnssChannels.IDS, data.channels.keys)
    }

    @Test
    fun `a source attached after stop is not started`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        assertTrue(rec.stop())
        val started = AtomicLong()
        val accepted = rec.attach(object : DataSource {
            override val name = "late"
            override fun start(sink: SampleSink) {
                started.incrementAndGet()
            }
            override fun stop() = Unit
        })
        assertEquals(false, accepted)
        assertEquals(0, started.get())
    }

    @Test
    fun `a source that never returns from stop does not keep the session from finishing`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val hang = java.util.concurrent.CountDownLatch(1)
        rec.attach(object : DataSource {
            override val name = "hung"
            override fun start(sink: SampleSink) = Unit
            override fun stop() = hang.await()
        })
        feed(rec, 10)
        try {
            assertTrue(rec.stop(timeoutMs = 5_000))
            assertEquals(SessionState.FINISHED, store.state(rec.dir))
        } finally {
            hang.countDown()
        }
    }

    @Test
    fun `each source is stopped exactly once`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val stops = AtomicLong()
        val firstStop = java.util.concurrent.CountDownLatch(1)
        rec.attach(object : DataSource {
            override val name = "counting"
            override fun start(sink: SampleSink) = Unit
            override fun stop() {
                stops.incrementAndGet()
                firstStop.countDown()
            }
        })
        assertTrue(rec.stop())
        // Sources stop on their own thread: wait for the first stop, then give the writer thread's
        // own stopAll time to (wrongly) stop it again.
        assertTrue(firstStop.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, stops.get())
    }

    @Test
    fun `a finished session does not wait for a slow source to stop`() {
        // Audit A14: stop() returning true means the session is finished, not that each source has stopped.
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stops = AtomicLong()
        rec.attach(object : DataSource {
            override val name = "slow"
            override fun start(sink: SampleSink) = Unit
            override fun stop() {
                entered.countDown()
                release.await()
                stops.incrementAndGet()
            }
        })
        feed(rec, 10)
        try {
            assertTrue(rec.stop(timeoutMs = 5_000))
            assertEquals(SessionState.FINISHED, store.state(rec.dir))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0L, stops.get())
        } finally {
            release.countDown()
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (stops.get() == 0L && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(1, stops.get())
    }

    @Test
    fun `a source reusing its value list after submit does not change what was recorded`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        val values = mutableListOf(ChannelValue(obdLike.id, 1.0))
        rec.defineChannel(obdLike)
        rec.submit(SampleSet(0, values))
        values[0] = ChannelValue(obdLike.id, 2.0)
        values.add(ChannelValue(obdLike.id, 3.0))
        rec.stop()
        val set = store.read(rec.id).sampleSets.single { s -> s.values.any { it.channelId == obdLike.id } }
        assertEquals(listOf(ChannelValue(obdLike.id, 1.0)), set.values)
    }

    @Test
    fun `oversized event text is cut, not fatal`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        rec.event("x".repeat(70_000), "y".repeat(70_000))
        feed(rec, 10)
        assertTrue(rec.stop())
        assertEquals("user", rec.finalStatus?.reason)
        val data = store.read(rec.id)
        assertTrue(data.events.any { it.text.length == 8_192 })
        assertEquals(10, data.sampleSets.count { s -> s.values.any { it.channelId == GnssChannels.LAT.id } })
    }

    @Test
    fun `channel names and oversized sets are rejected on the caller's thread`() {
        assertFailsWith<IllegalArgumentException> { ChannelDef(9, "n".repeat(257), "", "test") }
        assertFailsWith<IllegalArgumentException> {
            SampleSet(0, List(65_536) { ChannelValue(1, 0.0) })
        }
    }

    @Test
    fun `channel ids outside 16 bits are rejected before they reach the file`() {
        assertFailsWith<IllegalArgumentException> { ChannelValue(65_536, 1.0) }
        assertFailsWith<IllegalArgumentException> { ChannelValue(-1, 1.0) }
    }

    @Test
    fun `a source that fails to start is logged and the recording continues`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        rec.attach(object : DataSource {
            override val name = "broken"
            override fun start(sink: SampleSink): Unit = error("no bluetooth")
            override fun stop() = Unit
        })
        feed(rec, 10)
        rec.stop()
        val data = store.read(rec.id)
        assertTrue(data.events.any { it.kind == EventKinds.SOURCE_ERROR && it.text.contains("broken") })
        assertEquals(10, data.sampleSets.count { s -> s.values.any { it.channelId == GnssChannels.LAT.id } })
    }

    @Test
    fun `live consumers get samples while the disk is stalled`() {
        // Arek's 2026-10-17 drive: a disk write stalled for 2.2 s and the track screen lost its fixes.
        val store = SessionStore(tmp)
        val stalled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rec = Recorder(
            store,
            RecorderConfig(storageCheckIntervalMs = 0),
            storage = {
                if (Thread.currentThread().name.startsWith("recorder-writer")) {
                    stalled.countDown()
                    release.await()
                }
                1L shl 40
            },
        ).start(info())
        val live = AtomicLong()
        rec.listeners.add(object : RecorderListener {
            override fun onSamples(set: SampleSet) {
                live.incrementAndGet()
            }
        })
        assertTrue(stalled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        feed(rec, 50)
        val until = System.nanoTime() + 5_000_000_000L
        while (live.get() < 60 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(60, live.get(), "50 fixes and 10 OBD values while the writer waits on the disk")
        release.countDown()
        rec.stop()
        val fixes = store.read(rec.id).sampleSets.count { s -> s.values.any { it.channelId == GnssChannels.LAT.id } }
        assertEquals(50, fixes)
    }

    @Test
    fun `low storage warns, then stops cleanly with the data intact`() {
        val store = SessionStore(tmp)
        val free = AtomicLong(1L shl 40)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<RecorderStatus>()
        val rec = Recorder(
            store,
            RecorderConfig(
                flushIntervalMs = 20,
                syncIntervalMs = 50,
                storageCheckIntervalMs = 10,
                storageWarnBytes = 1_000,
                storageStopBytes = 100,
            ),
            storage = { free.get() },
        ).start(info())
        rec.listeners.add(object : RecorderListener {
            override fun onStatus(status: RecorderStatus) {
                statuses += status
            }
        })
        feed(rec, 100)
        free.set(500)
        Thread.sleep(300)
        assertTrue(statuses.any { it is RecorderStatus.StorageLow })
        free.set(50)
        assertTrue(rec.awaitStopped(5_000))
        assertEquals("storage_full", rec.finalStatus?.reason)
        val data = store.read(rec.id)
        assertEquals(SessionState.FINISHED, data.state)
        assertEquals(100, data.sampleSets.count { s -> s.values.any { it.channelId == GnssChannels.LAT.id } })
        assertTrue(data.events.any { it.kind == EventKinds.STORAGE_LOW })
        assertTrue(data.events.any { it.kind == EventKinds.STORAGE_FULL })
    }

    @Test
    fun `video markers carry the offset to session time`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        rec.videoStarted("phone", "VID_0001.mp4")
        rec.videoStopped("phone", "VID_0001.mp4")
        rec.stop()
        val events = store.read(rec.id).events
        val start = events.single { it.kind == EventKinds.VIDEO_START }
        val stop = events.single { it.kind == EventKinds.VIDEO_STOP }
        assertEquals("camera=phone file=VID_0001.mp4", start.text)
        assertTrue(stop.tMicros >= start.tMicros)
    }

    @Test
    fun `fake gnss source records in real time`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info(emptyList()))
        rec.attach(FakeGnssSource(gnss))
        Thread.sleep(400)
        rec.stop()
        val fixes = store.read(rec.id).sampleSets
        assertTrue(fixes.size in 5..15, "got ${fixes.size} fixes in 0.4 s at 25 Hz")
        assertContentEquals(fixes.indices.map { gnss.fix(it.toLong()) }, fixes.map { it.values })
    }

    @Test
    fun `reading events only leaves the samples out and keeps everything else`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(info())
        feed(rec, 50)
        rec.event("coach_cue", "brake later")
        assertTrue(rec.stop())
        val whole = store.read(rec.id)
        val events = store.read(store.dir(rec.id), samples = false)
        assertTrue(whole.sampleSets.isNotEmpty())
        assertEquals(emptyList(), events.sampleSets)
        assertEquals(whole.events, events.events)
        assertEquals(whole.channels, events.channels)
        assertEquals(whole.info, events.info)
        assertEquals(whole.end, events.end)
    }
}
