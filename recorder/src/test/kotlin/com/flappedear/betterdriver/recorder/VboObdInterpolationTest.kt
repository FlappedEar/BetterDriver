package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBD-II channels in a VBO are interpolated between readings, as RaceChrono's export does (FBBD-303). */
class VboObdInterpolationTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()
    private val rpm = ChannelDef(300, "obd_rpm", "rpm", "obd", 0)

    /** The rpm column of a VBO made from fixes at [fixMicros] and rpm [readings] (time to value). */
    private fun export(fixMicros: List<Long>, readings: List<Pair<Long, Double>>): List<Double> {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL + rpm))
        val fixes = fixMicros.mapIndexed { i, t -> SampleSet(t, gnss.fix(i.toLong())) }
        val obd = readings.map { (t, v) -> SampleSet(t, listOf(ChannelValue(rpm.id, v))) }
        (fixes + obd).sortedBy { it.tMicros }.forEach(rec::submit)
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        return VboValidator.validate(out.toString(Charsets.UTF_8)).rows.map { it.last().toDouble() }
    }

    @Test
    fun `a fix between two readings gets the value on the line between them`() {
        val fixes = (0..5).map { it * 40_000L }
        val values = export(fixes, listOf(0L to 1000.0, 200_000L to 2000.0))
        assertEquals(listOf(1000.0, 1200.0, 1400.0, 1600.0, 1800.0, 2000.0), values)
    }

    @Test
    fun `readings more than a second apart are held, not joined`() {
        val fixes = listOf(0L, 500_000L, 1_000_000L, 1_500_000L)
        val values = export(fixes, listOf(0L to 800.0, 1_500_000L to 3000.0))
        assertEquals(listOf(800.0, 800.0, 800.0, 3000.0), values)
    }

    @Test
    fun `before the first reading the first is used, after the last the last is held`() {
        val fixes = listOf(0L, 100_000L, 200_000L, 300_000L, 400_000L)
        val values = export(fixes, listOf(100_000L to 1000.0, 300_000L to 3000.0))
        assertEquals(listOf(1000.0, 1000.0, 2000.0, 3000.0, 3000.0), values)
    }

    /**
     * Arek's RaceChrono export from Jastrząb (180 s of rpm at 10 Hz rows, no positions). Every 5th
     * row is taken as an OBD reading (2 Hz, about RaceChrono's own rate there) and the VBO rows are
     * made at all 10 Hz rows. The interpolated rows come back to RaceChrono's own values far more
     * closely than held ones would.
     */
    @Test
    fun `on Arek's RaceChrono rpm trace the export comes back to RaceChrono's own rows`() {
        val trace = javaClass.getResource("/rpm/jastrzab-racechrono-rpm.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(",").let { (s, v) -> (s.toDouble() * 1_000_000).toLong() to v.toDouble() } }
        val readings = trace.filterIndexed { i, _ -> i % 5 == 0 }
        val values = export(trace.map { it.first + 1 }, readings)
        val interpolated = values.indices.map { abs(values[it] - trace[it].second) }.average()
        var held = 0.0
        trace.forEachIndexed { i, (_, v) -> held += abs(readings[i / 5].second - v) }
        held /= trace.size
        assertTrue(interpolated < 25.0, "interpolated mean error $interpolated rpm")
        assertTrue(interpolated < held / 4, "interpolated $interpolated rpm, held $held rpm")
    }

    /**
     * FBBD-320: the FR-S steering angle from CAN, 50 a second, is a VBO column of its own that FlappedEar
     * Overlays reads by name: `obd_steering_angle`, unit `deg`, positive with the wheel turned left.
     */
    @Test
    fun `the steering angle is the column obd_steering_angle in degrees`() {
        val steering = ChannelDef(301, "obd_steering_angle", "deg", "obd", 2)
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL + steering))
        val fixes = (0..4).map { SampleSet(it * 40_000L, gnss.fix(it.toLong())) }
        val angles = (0..8).map { SampleSet(it * 20_000L + 1, listOf(ChannelValue(steering.id, -90.0 + it * 10.0))) }
        (fixes + angles).sortedBy { it.tMicros }.forEach(rec::submit)
        rec.stop()
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out)
        val vbo = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals("obd_steering_angle", vbo.header.last())
        assertEquals("obd_steering_angle", vbo.columnNames.last())
        assertEquals("deg", vbo.units.last())
        val values = vbo.rows.map { it.last().toDouble() }
        assertEquals(-90.0, values.first())
        assertTrue(values.zipWithNext().all { (a, b) -> b > a }, values.toString())
    }
}
