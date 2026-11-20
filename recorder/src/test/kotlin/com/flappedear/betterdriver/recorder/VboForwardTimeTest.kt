package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FBBD-436: the VBO time column only goes forward. iOS Core Location hands over its cached fix after
 * the first new ones (stamped seconds earlier) and can deliver one fix twice; neither gets a row,
 * and neither counts as a lost sample in the export check (FBBD-14).
 */
class VboForwardTimeTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    private fun export(
        fixes: List<SampleSet>,
        startUtcMillis: Long = gnss.startUtcMillis,
    ): Pair<VboExporter.Written, List<String>> {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(startUtcMillis, channels = GnssChannels.ALL))
        fixes.forEach(rec::submit)
        rec.stop()
        val out = ByteArrayOutputStream()
        val written = VboExporter.export(store.read(rec.id), out)
        val parsed = VboValidator.validate(out.toString(Charsets.UTF_8))
        val time = parsed.header.indexOf("time")
        return written to parsed.rows.map { it[time] }
    }

    /** Fix [n] of the fake receiver, recorded at [tMicros]. */
    private fun fix(n: Long, tMicros: Long) = SampleSet(tMicros, gnss.fix(n))

    @Test
    fun `a cached fix stamped before the rows already written gets no row`() {
        // Fixes 100..104 (4.00 s to 4.16 s), then the cached fix 10 (0.40 s), then 105 and 106.
        val sets = (0L..4L).map { fix(100 + it, it * 40_000) } +
            fix(10, 200_000) +
            (5L..6L).map { fix(100 + it, 200_001 + it * 40_000) }
        val (written, times) = export(sets)
        assertEquals(7, times.size)
        assertEquals(VboExporter.Written(rows = 7, fixes = 7, repeated = 1), written)
    }

    @Test
    fun `the same fix delivered twice is written once`() {
        val sets = listOf(fix(0, 0), fix(1, 40_000), fix(1, 41_000), fix(2, 80_000))
        val (written, times) = export(sets)
        assertEquals(3, times.size)
        assertEquals(times, times.distinct())
        assertEquals(1, written.repeated)
        assertEquals(written.rows, written.fixes)
    }

    @Test
    fun `fixes in time order are all written`() {
        val sets = (0L until 50L).map { fix(it, it * 40_000) }
        val (written, times) = export(sets)
        assertEquals(50, times.size)
        assertEquals(VboExporter.Written(rows = 50, fixes = 50, repeated = 0), written)
    }

    @Test
    fun `a cached fix handed over first, stamped seconds before the start, gets no row`() {
        // iOS 2026-11-02: the cached fix (3.52 s before START) arrived first, then the new ones.
        val sets = listOf(fix(-88, 0)) + (0L..4L).map { fix(it, 10_000 + it * 40_000) }
        val (written, times) = export(sets)
        assertEquals(listOf("080000.000", "080000.040", "080000.080", "080000.120", "080000.160"), times)
        assertEquals(VboExporter.Written(rows = 5, fixes = 5, repeated = 1), written)
    }

    @Test
    fun `a fix stamped under two seconds before the start keeps its row`() {
        // A receiver's clock and the phone's differ a little; one second is not a cached fix.
        val sets = listOf(fix(-25, 0)) + (0L..4L).map { fix(it, 10_000 + it * 40_000) }
        val (written, times) = export(sets)
        assertEquals("075959.000", times.first())
        assertEquals(VboExporter.Written(rows = 6, fixes = 6, repeated = 0), written)
    }

    @Test
    fun `a phone clock set a minute ahead keeps every row of a long recording`() {
        // Every fix is stamped a minute before the phone's clock: an offset, not a cached fix.
        val sets = (0L until 3_000L).map { fix(it, it * 40_000) }
        val (written, times) = export(sets, startUtcMillis = gnss.startUtcMillis + 60_000)
        assertEquals(3_000, times.size)
        assertEquals(VboExporter.Written(rows = 3_000, fixes = 3_000, repeated = 0), written)
    }

    @Test
    fun `a cached fix after a clock a minute ahead is still left out`() {
        val sets = listOf(fix(-88, 0)) + (0L until 1_000L).map { fix(it, 10_000 + it * 40_000) }
        val (written, times) = export(sets, startUtcMillis = gnss.startUtcMillis + 60_000)
        assertEquals("080000.000", times.first())
        assertEquals(VboExporter.Written(rows = 1_000, fixes = 1_000, repeated = 1), written)
    }
}
