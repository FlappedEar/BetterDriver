package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A source that hands over NaN or infinity (Moto Park 2026-11-08: the VBO must be flawless whatever
 * else breaks): the VBO still reads in other tools, with 0 for the value, and keeps one row per fix.
 */
class VboNotANumberTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `a value that is not a number never reaches the VBO`() {
        val gnss = FakeGnss()
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(
            SessionInfo(
                startUtcMillis = gnss.startUtcMillis,
                car = "Scion FR-S",
                track = "Fake ring",
                devices = listOf("fake-gnss", "fake-tpms"),
                startFinish = gnss.startFinish,
                channels = GnssChannels.ALL,
            ),
        )
        rec.defineChannel(ChannelDef(TyreId, "tyre_pressure_fl", "bar", "tpms", 2))
        val fixes = 250
        for (n in 0 until fixes.toLong()) {
            rec.submit(SampleSet(n * 40_000, gnss.fix(n)))
            val bad = when (n % 3) {
                0L -> Double.NaN
                1L -> Double.POSITIVE_INFINITY
                else -> 2.1
            }
            rec.submit(SampleSet(n * 40_000 + 1, listOf(ChannelValue(TyreId, bad))))
        }
        check(rec.stop())
        val out = ByteArrayOutputStream()
        val written = VboExporter.export(store.read(rec.id), out)
        val text = out.toString(Charsets.UTF_8)
        assertEquals(fixes, written.rows)
        assertFalse(text.contains("NaN") || text.contains("Infinity"), "a value that is not a number")
        assertEquals(fixes, VboValidator.validate(text).rows.size)
    }

    private companion object {
        const val TyreId = 900
    }
}
