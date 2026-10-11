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
}
