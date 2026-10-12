package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where every channel's values come from (FBBD-174): each kind, its storage and its derivation. */
class ProvenanceTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    @Test
    fun `only the measured kinds count as measured`() {
        val measured = setOf(
            Provenance.MEASURED_GPS,
            Provenance.MEASURED_GNSS_RECEIVER,
            Provenance.MEASURED_ACCELEROMETER,
            Provenance.MEASURED_OBD,
            Provenance.MEASURED_CAN,
            Provenance.MEASURED_SENSOR,
        )
        for (kind in Provenance.entries) assertEquals(kind in measured, kind.measured, kind.name)
        for (kind in listOf(Provenance.CALCULATED, Provenance.INFERRED, Provenance.FUSED, Provenance.INTERPOLATED)) {
            assertFalse(kind.measured, kind.name)
        }
    }

    @Test
    fun `every kind is parsed back from its stored name, and an unknown name is not`() {
        for (kind in Provenance.entries) assertEquals(kind, Provenance.parse(kind.name))
        assertNull(Provenance.parse("MEASURED_BY_MAGIC"))
        assertNull(Provenance.parse(null))
    }

    @Test
    fun `each source's channels get the provenance of what measures them`() {
        val cases = listOf(
            Triple("gnss", "gnss_lat", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("racebox", "gnss_hacc", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("racebox", "accel_x", Provenance.MEASURED_ACCELEROMETER),
            Triple("racebox", "gyro_z", Provenance.MEASURED_ACCELEROMETER),
            Triple("racebox", "racebox_input", Provenance.MEASURED_SENSOR),
            Triple("nmea", "rc_accel_y", Provenance.MEASURED_ACCELEROMETER),
            Triple("nmea", "nmea_hdop", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("vbox_sport", "vbox_dgps", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("dragy", "dragy_hacc", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("racechrono_diy", "diy_hdop", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("lns", "lns_compass_heading", Provenance.MEASURED_GNSS_RECEIVER),
            Triple("lns", "lns_total_distance", Provenance.CALCULATED),
            Triple("heart_rate", "heart_rate", Provenance.MEASURED_SENSOR),
            Triple("obd", "rpm", Provenance.MEASURED_OBD),
            Triple("can", "brake_pressure", Provenance.MEASURED_CAN),
            Triple("import", "accel_x", Provenance.CALCULATED),
            Triple("import", "Throttle", Provenance.UNKNOWN),
            Triple("someone-elses", "x", Provenance.UNKNOWN),
        )
        for ((source, name, expected) in cases) {
            assertEquals(expected, Provenance.derive(source, name), "$source/$name")
            assertEquals(expected, ChannelDef(9, name, "", source).provenance, "default of $source/$name")
        }
        assertTrue(FakeGnssSource.Channels.all { it.provenance == Provenance.SIMULATED })
    }

    @Test
    fun `a session stored without provenance derives it from its devices and import`() {
        val lat = GnssChannels.LAT
        val speed = GnssChannels.SPEED_KMH
        val accel = ChannelDef(110, "accel_x", "g", "racebox")
        fun of(def: ChannelDef, devices: List<String> = emptyList(), imported: ImportOrigin? = null) =
            Provenance.forSession(def, devices, imported)
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, of(lat, listOf("racebox")))
        assertEquals(Provenance.SIMULATED, of(lat, listOf("fake-gnss")))
        assertEquals(Provenance.MEASURED_ACCELEROMETER, of(accel, listOf("racebox")))
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, of(speed, imported = ImportOrigin("VBO", "a.vbo")))
        assertEquals(Provenance.MEASURED_GPS, of(lat, imported = ImportOrigin("GPX", "a.gpx")))
        assertEquals(Provenance.CALCULATED, of(speed, imported = ImportOrigin("GPX", "a.gpx")))
        assertEquals(Provenance.CALCULATED, of(GnssChannels.HEADING, imported = ImportOrigin("GPX", "a.gpx")))
        assertEquals(Provenance.MEASURED_GPS, of(speed, imported = ImportOrigin("RaceChrono CSV", "a.csv")))
    }

    /** A source that defines one channel of every kind, as an OBD reader or a fusion filter would. */
    private class EveryKindSource : DataSource {
        override val name = "every-kind"

        override fun start(sink: SampleSink) {
            for (def in everyKind) sink.defineChannel(def)
            sink.submit(SampleSet(sink.nowMicros(), everyKind.map { ChannelValue(it.id, 1.0) }))
        }

        override fun stop() = Unit

        companion object {
            val everyKind = Provenance.entries.mapIndexed { i, kind ->
                ChannelDef(700 + i, "kind_${kind.name.lowercase()}", "", "test", 2, kind)
            }
        }
    }

    @Test
    fun `every channel's provenance is stored and read back, whichever kind it is`() {
        val store = SessionStore(tmp)
        val info = SessionInfo(gnss.startUtcMillis, devices = listOf("every-kind"), channels = GnssChannels.ALL)
        val rec = Recorder(store).start(info)
        rec.attach(EveryKindSource())
        rec.submit(SampleSet(1, gnss.fix(0)))
        rec.stop()

        val read = store.read(rec.id)
        for (def in EveryKindSource.everyKind) assertEquals(def.provenance, read.channels.getValue(def.id).provenance)
        for (def in GnssChannels.ALL) assertEquals(def.provenance, read.channels.getValue(def.id).provenance)
        // The metadata names a provenance for every channel the session holds, not only the first ones.
        val meta = SessionFiles.readProps(File(rec.dir, SessionFiles.META))!!
        val stored = (0 until meta.getProperty("channels").toInt()).associate {
            meta.getProperty("channel.$it.id").toInt() to meta.getProperty("channel.$it.provenance")
        }
        assertEquals(read.channels.keys, stored.keys)
        for ((id, def) in read.channels) assertEquals(def.provenance.name, stored[id], def.name)
    }

    @Test
    fun `a stored provenance wins over what the source would suggest`() {
        val store = SessionStore(tmp)
        val fused = GnssChannels.SPEED_KMH.copy(provenance = Provenance.FUSED)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = listOf(GnssChannels.LAT, fused)))
        rec.submit(SampleSet(1, gnss.fix(0)))
        rec.stop()
        val read = store.read(rec.id)
        assertEquals(Provenance.FUSED, read.channels.getValue(fused.id).provenance)
        assertEquals(Provenance.FUSED, read.info.channels.first { it.id == fused.id }.provenance)
    }

    @Test
    fun `Demo GNSS is recorded as simulated, never as measured`() {
        val store = SessionStore(tmp)
        val info = SessionInfo(gnss.startUtcMillis, devices = listOf("fake-gnss"), channels = FakeGnssSource.Channels)
        val rec = Recorder(store).start(info)
        rec.attach(FakeGnssSource(gnss))
        rec.stop()
        val read = store.read(rec.id)
        assertTrue(read.channels.values.all { it.provenance == Provenance.SIMULATED })
    }

    @Test
    fun `an older session gets its provenance from its source when read, chunk channels included`() {
        val store = SessionStore(tmp)
        val dir = File(tmp, "20260912-100000-a1b2").apply { mkdirs() }
        // As 0.3.1 wrote it: no provenance keys, and the RaceBox's accelerometer only in the chunk.
        val props = Properties().apply {
            setProperty("format", "1")
            setProperty("id", dir.name)
            setProperty("startUtcMillis", gnss.startUtcMillis.toString())
            setProperty("devices", "racebox")
            setProperty("channels", "1")
            setProperty("channel.0.id", "2")
            setProperty("channel.0.name", "gnss_lat")
            setProperty("channel.0.unit", "deg")
            setProperty("channel.0.source", "gnss")
            setProperty("channel.0.decimals", "8")
        }
        SessionFiles.writeAtomically(File(dir, SessionFiles.META), props)
        val accel = ChannelDef(110, "accel_x", "g", "racebox", 3)
        File(dir, SessionFiles.chunkName(1)).outputStream().use {
            it.write(Frames.encode(Record.Channel(accel)))
            it.write(Frames.encode(Record.Samples(SampleSet(0, listOf(ChannelValue(2, 52.0), ChannelValue(110, 0.1))))))
        }
        val read = store.read(dir.name)
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, read.channels.getValue(2).provenance)
        assertEquals(Provenance.MEASURED_ACCELEROMETER, read.channels.getValue(110).provenance)
    }

    @Test
    fun `an import's source details survive the metadata`() {
        val origin = ImportOrigin("GPX", "ride.gpx", receiver = "Garmin Edge 530", sampleRateHz = 1.0)
        val lat = GnssChannels.LAT.copy(provenance = Provenance.MEASURED_GPS)
        val speed = GnssChannels.SPEED_KMH.copy(provenance = Provenance.CALCULATED)
        val info = SessionInfo(gnss.startUtcMillis, channels = listOf(lat, speed), imported = origin)
        val back = SessionFiles.propsToMeta(SessionFiles.metaToProps("x", info))
        assertEquals(origin, back.imported)
        assertEquals(listOf(lat, speed), back.channels)
    }
}
