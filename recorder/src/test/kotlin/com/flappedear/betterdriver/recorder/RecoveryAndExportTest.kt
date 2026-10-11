package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryAndExportTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    /**
     * Writes a session the way a recorder killed mid-write leaves it: metadata, chunks of framed
     * records with no end record, a half-written last frame, no finished marker, no lock held.
     */
    private fun crashedSession(store: SessionStore, fixes: Int, perChunk: Int = 100): String {
        val (id, dir) = store.createSession(
            SessionInfo(
                gnss.startUtcMillis, "FR-S", "Fake ring", listOf("fake-gnss"), gnss.startFinish, GnssChannels.ALL,
            ),
        )
        var i = 0
        var chunk = 1
        while (i < fixes) {
            File(dir, SessionFiles.chunkName(chunk++)).outputStream().use { out ->
                GnssChannels.ALL.forEach { out.write(Frames.encode(Record.Channel(it))) }
                repeat(minOf(perChunk, fixes - i)) {
                    out.write(Frames.encode(Record.Samples(SampleSet(i * 40_000L, gnss.fix(i.toLong())))))
                    i++
                }
                val partial = Frames.encode(Record.Samples(SampleSet(i * 40_000L, gnss.fix(i.toLong()))))
                if (i >= fixes) out.write(partial, 0, partial.size / 2)
            }
        }
        return id
    }

    private fun digest(dir: File): Map<String, String> = dir.listFiles()!!
        .filter { it.name.startsWith("chunk-") }
        .associate { f ->
            f.name to MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        }

    @Test
    fun `an interrupted session is detected, recovered and keeps every complete sample`() {
        val store = SessionStore(tmp)
        val id = crashedSession(store, 250)
        assertEquals(SessionState.INTERRUPTED, store.list().single().state)

        val before = digest(store.dir(id))
        val reports = store.recoverInterrupted()
        assertEquals(before, digest(store.dir(id)), "recovery must not change raw files")

        val r = reports.single()
        assertEquals(250, r.sampleSets)
        assertEquals(249 * 40_000L, r.lastSampleMicros)
        assertTrue(r.truncatedBytes > 0)
        assertEquals(SessionState.RECOVERED, store.list().single().state)
        assertEquals(emptyList(), store.recoverInterrupted(), "recovering twice does nothing")

        val data = store.read(id)
        assertNull(data.end)
        assertEquals((0 until 250).map { gnss.fix(it.toLong()) }, data.sampleSets.map { it.values })
    }

    @Test
    fun `a recovered session exports to a valid VBO with every fix`() {
        val store = SessionStore(tmp)
        val id = crashedSession(store, 250)
        store.recoverInterrupted()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(id), out)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals(250, p.rows.size)
        assertTrue(p.body["[comments]"]!!.contains("Recovered after an interrupted recording"))
        assertEquals(listOf("sats", "time", "lat", "long", "velocity", "heading", "height"), p.columnNames)
        assertEquals("080000.000", p.rows.first()[1])
        assertEquals("080009.960", p.rows.last()[1])
        assertTrue(p.body["[laptiming]"]!!.single().startsWith("Start "))
    }

    @Test
    fun `a session being recorded is not treated as interrupted`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        assertEquals(SessionState.RECORDING, store.list().single().state)
        assertEquals(emptyList(), store.recoverInterrupted())
        rec.stop()
    }

    @Test
    fun `export holds slow channels and leaves out silent ones`() {
        val store = SessionStore(tmp)
        val temp = ChannelDef(200, "oil temp", "C", "test", 1)
        val silent = ChannelDef(201, "never", "x", "test", 1)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL + temp + silent))
        for (i in 0 until 10) {
            rec.submit(SampleSet(i * 40_000L, gnss.fix(i.toLong())))
            if (i == 3) rec.submit(SampleSet(i * 40_000L + 1, listOf(ChannelValue(temp.id, 95.0))))
            if (i == 7) rec.submit(SampleSet(i * 40_000L + 1, listOf(ChannelValue(temp.id, 97.5))))
        }
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals("oil_temp", p.columnNames.last())
        assertEquals(listOf("C"), p.units)
        val temps = p.rows.map { it.last() }
        // Before the first value: the first value; then held until the next value arrives.
        assertEquals(listOf("95.0", "95.0", "95.0", "95.0", "95.0", "95.0", "95.0", "95.0", "97.5", "97.5"), temps)
        assertTrue(p.rows.all { it.size == 8 })
    }

    @Test
    fun `custom channels never take a standard VBO name`() {
        val store = SessionStore(tmp)
        val sats = ChannelDef(300, "satellites", "", "test", 0)
        val lat = ChannelDef(301, "lat", "deg", "test", 1)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL + sats + lat))
        rec.submit(SampleSet(0, gnss.fix(0) + ChannelValue(sats.id, 9.0) + ChannelValue(lat.id, 1.0)))
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals(listOf("satellites_300", "lat_301"), p.header.drop(7))
        assertEquals(listOf("", "deg"), p.units)
    }

    @Test
    fun `a suffixed VBO name that is already taken gets another suffix`() {
        val store = SessionStore(tmp)
        val first = ChannelDef(299, "satellites_300", "", "test", 0)
        val second = ChannelDef(300, "satellites", "", "test", 0)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL + first + second))
        rec.submit(SampleSet(0, gnss.fix(0) + ChannelValue(first.id, 1.0) + ChannelValue(second.id, 2.0)))
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals(listOf("satellites_300", "satellites_300_2"), p.header.drop(7))
    }

    @Test
    fun `fixes without GNSS time use session start plus elapsed time`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val fix = listOf(ChannelValue(GnssChannels.LAT.id, 52.0), ChannelValue(GnssChannels.LON.id, 21.0))
        rec.submit(SampleSet(1_500_000, fix))
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals("080001.500", p.rows.single()[1])
    }
}
