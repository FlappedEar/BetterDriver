package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.export.CsvExporter
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A track day in one test (Moto Park 2026-11-08): several 25 min sessions with every source the car
 * carries, link drops, then a phone that runs out of space. Nothing the sources hand over may be lost:
 * every value is read back, the VBO has one row per fix, and the export's time only goes forward.
 */
class TrackDaySoakTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    private val steering = ChannelDef(SteeringId, "steering angle", "deg", "can", 1)
    private val brake = ChannelDef(BrakeId, "brake pressure", "bar", "can", 1)
    private val rpm = ChannelDef(RpmId, "rpm", "rpm", "can", 0)
    private val coolant = ChannelDef(CoolantId, "coolant temp", "C", "obd", 0)
    private val oil = ChannelDef(OilId, "oil temp", "C", "obd", 0)
    private val heart = ChannelDef(HeartId, "heart rate", "bpm", "hr", 0)
    private val tyres = (0 until TyreChannels).map { ChannelDef(TyreFirstId + it, "tyre $it", "bar", "tpms", 2) }
    private val defs = listOf(steering, brake, rpm, coolant, oil, heart) + tyres

    private fun info() = SessionInfo(
        startUtcMillis = gnss.startUtcMillis,
        car = "Scion FR-S",
        track = "Fake ring",
        devices = listOf("fake-gnss", "fake-can", "fake-obd", "fake-tpms", "fake-hr"),
        startFinish = gnss.startFinish,
        channels = GnssChannels.ALL,
    )

    /** What one simulated session handed to the recorder, per channel. */
    private class Sent {
        val counts = HashMap<Int, Int>()
        var fixes = 0
        fun add(values: List<ChannelValue>) = values.forEach { counts.merge(it.channelId, 1, Int::plus) }
    }

    /** Streams wanted in a simulated second; the gaps model a link that drops and comes back. */
    private fun feedSecond(rec: Recording, s: Int, sent: Sent) {
        val base = s * MicrosPerSecond
        val gnssUp = s !in GnssGap
        val obdUp = s !in ObdGap
        if (gnssUp) {
            for (k in 0 until GnssHz) {
                val n = s.toLong() * GnssHz + k
                submit(rec, sent, n * (MicrosPerSecond / GnssHz), gnss.fix(n))
                sent.fixes++
            }
        }
        for (k in 0 until CanHz) {
            val v = listOf(ChannelValue(SteeringId, (k % 90).toDouble()), ChannelValue(BrakeId, (k % 10).toDouble()))
            submit(rec, sent, base + k * (MicrosPerSecond / CanHz) + 1, v)
        }
        for (k in 0 until RpmHz) {
            submit(rec, sent, base + k * (MicrosPerSecond / RpmHz) + 2, listOf(ChannelValue(RpmId, 3_000.0 + k)))
        }
        if (obdUp && s % ObdEverySeconds == 0) {
            submit(rec, sent, base + 3, listOf(ChannelValue(CoolantId, 90.0), ChannelValue(OilId, 100.0 + s % 7)))
        }
        submit(rec, sent, base + 4, tyres.mapIndexed { i, d -> ChannelValue(d.id, 2.0 + i / 100.0 + s % 3 / 100.0) })
        submit(rec, sent, base + 5, listOf(ChannelValue(HeartId, 120.0 + s % 30)))
    }

    private fun submit(rec: Recording, sent: Sent, t: Long, values: List<ChannelValue>) {
        rec.submit(SampleSet(t, values))
        sent.add(values)
    }

    /** About as fast as the writer: a day of data in seconds, but never a full queue. */
    private fun pace(s: Int) {
        if (s % PaceEverySeconds == 0) Thread.sleep(1)
    }

    private fun recordSession(store: SessionStore, seconds: Int): Pair<Recording, Sent> {
        val rec = Recorder(store).start(info())
        defs.forEach(rec::defineChannel)
        val sent = Sent()
        for (s in 0 until seconds) {
            feedSecond(rec, s, sent)
            pace(s)
        }
        assertTrue(rec.stop(), "session did not finish")
        return rec to sent
    }

    private fun readCounts(data: SessionData): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        data.sampleSets.forEach { set -> set.values.forEach { counts.merge(it.channelId, 1, Int::plus) } }
        return counts
    }

    private fun checkExports(data: SessionData, fixes: Int) {
        val vbo = ByteArrayOutputStream()
        val written = VboExporter.export(data, vbo)
        assertEquals(fixes, written.fixes, "VBO fixes")
        assertEquals(fixes, written.rows, "one VBO row per fix")
        assertEquals(0, written.repeated, "no fix may be left out of the VBO")
        val parsed = VboValidator.validate(vbo.toString(Charsets.UTF_8))
        assertEquals(fixes, parsed.rows.size)
        val csv = ByteArrayOutputStream()
        CsvExporter.export(data, csv)
        assertTrue(csv.size() > fixes, "CSV export is empty")
    }

    @Test
    fun `a track day of full sessions with link drops loses nothing`() {
        val store = SessionStore(tmp)
        repeat(Sessions) { n ->
            val started = System.nanoTime()
            val (rec, sent) = recordSession(store, SessionSeconds)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(0L, rec.droppedCount, "session $n: queue dropped samples")
            assertEquals("user", rec.finalStatus?.reason)
            assertEquals(SessionState.FINISHED, store.state(rec.dir))
            val data = store.read(rec.id)
            assertEquals(0L, data.truncatedBytes + data.skippedBytes, "session $n: damaged bytes on disk")
            assertEquals(sent.counts, readCounts(data), "session $n: values on disk differ from values sent")
            assertTrue(defs.all { it.id in data.channels }, "session $n: a channel definition is missing")
            checkExports(data, sent.fixes)
            val bytes = rec.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            println("soak session $n: ${sent.fixes} fixes, ${data.sampleSets.size} sets, $bytes bytes, $ms ms")
        }
        assertEquals(Sessions, store.list().size)
    }

    @Test
    fun `a phone running out of space keeps everything written before the stop`() {
        val store = SessionStore(tmp)
        val free = AtomicLong(1L shl 40)
        val rec = Recorder(
            store,
            RecorderConfig(storageCheckIntervalMs = 10, storageWarnBytes = 1_000, storageStopBytes = 100),
            storage = { free.get() },
        ).start(info())
        defs.forEach(rec::defineChannel)
        val sent = Sent()
        for (s in 0 until LowStorageAtSecond) {
            feedSecond(rec, s, sent)
            pace(s)
        }
        // Let the writer put all of it on disk, then the space runs out while the sources keep going.
        Thread.sleep(SettleMs)
        val beforeStop = HashMap(sent.counts)
        free.set(50)
        var s = LowStorageAtSecond
        while (!rec.isStopped && s < SessionSeconds) feedSecond(rec, s++, sent)
        assertTrue(rec.awaitStopped(5_000), "recorder did not stop on low storage")
        assertEquals("storage_full", rec.finalStatus?.reason)
        assertEquals(SessionState.FINISHED, store.state(rec.dir))
        val data = store.read(rec.id)
        val onDisk = readCounts(data)
        for ((id, count) in beforeStop) {
            assertTrue((onDisk[id] ?: 0) >= count, "channel $id: ${onDisk[id]} on disk, $count sent before the stop")
        }
        assertTrue(data.events.any { it.kind == EventKinds.STORAGE_FULL })
        val fixes = data.sampleSets.count { set -> set.values.any { it.channelId == GnssChannels.UTC_MS.id } }
        checkExports(data, fixes)
    }

    private companion object {
        const val Sessions = 3
        const val SessionSeconds = 25 * 60
        const val GnssHz = 25
        const val CanHz = 45
        const val RpmHz = 90
        const val ObdEverySeconds = 2
        const val TyreChannels = 8
        const val MicrosPerSecond = 1_000_000L
        const val PaceEverySeconds = 2
        const val LowStorageAtSecond = 300
        const val SettleMs = 500L
        val GnssGap = 600 until 603
        val ObdGap = 900 until 910

        const val SteeringId = 700
        const val BrakeId = 701
        const val RpmId = 702
        const val CoolantId = 801
        const val OilId = 813
        const val HeartId = 400
        const val TyreFirstId = 900
    }
}
