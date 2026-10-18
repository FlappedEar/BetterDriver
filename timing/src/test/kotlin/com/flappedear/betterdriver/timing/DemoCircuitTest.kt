@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.track.findSessionLoop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The demo circuit (Demo track receiver): a made-up track that times, varies and loops like a real one. */
class DemoCircuitTest {
    private val gnss = DemoCircuit(startUtcMillis = 1_790_000_000_000L)
    private val track = DemoCircuit.track

    /** [minutes] of fixes at 25 Hz, as the recorder stamps them. */
    private fun fixes(minutes: Int, g: DemoCircuit = gnss): List<Fix> =
        (0L until minutes * 60L * g.rateHz).map { n ->
            assertNotNull(SampleSet(n * 1_000_000L / g.rateHz, g.fix(n)).toFix())
        }

    @Test
    fun `it closes, and its track has a line and two sectors`() {
        assertTrue(track.timeable)
        assertEquals(3, track.sectorCount)
        assertEquals(2_000.0, DemoCircuit.lengthMeters, 200.0)
    }

    @Test
    fun `laps are timed with every sector, lap times vary by tenths and the first is an out lap`() {
        val book = timeSession(track, fixes(15).asSequence())
        assertEquals(LapKind.Out, book.laps.first().kind)
        val timed = book.laps.filter { it.kind == LapKind.Timed }
        assertTrue(timed.size >= 10, "${timed.size} timed laps in 15 min")
        val seconds = timed.map { it.timeMillis / 1_000.0 }
        assertTrue(seconds.all { it in 50.0..100.0 }, "lap times $seconds")
        val spread = seconds.max() - seconds.min()
        assertTrue(spread in 0.2..5.0, "lap times spread over $spread s: $seconds")
        assertTrue(timed.all { lap -> lap.sectorMicros.size == 3 && lap.sectorMicros.all { it != null } })
    }

    @Test
    fun `the car brakes for the corners and never jumps`() {
        val fixes = fixes(5)
        val kmh = (0L until fixes.size).map { n ->
            gnss.fix(n).single { it.channelId == GnssChannels.SPEED_KMH.id }.value
        }
        assertTrue(kmh.max() in 150.0..200.0, "top ${kmh.max()} km/h")
        // The hairpin, after the first minute of driving.
        assertTrue(kmh.drop(1_500).min() in 40.0..70.0, "slowest ${kmh.drop(1_500).min()} km/h")
        val steps = fixes.zipWithNext { a, b -> distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }
        // 198 km/h is 2.2 m per fix at 25 Hz; the line between laps is no jump either.
        assertTrue(steps.max() < 3.0, "longest step ${steps.max()} m")
    }

    @Test
    fun `fix n is always the same, asked for in order or not`() {
        val other = DemoCircuit(startUtcMillis = gnss.startUtcMillis)
        val late = other.fix(30_000)
        repeat(30_001) { gnss.fix(it.toLong()) }
        assertEquals(late, gnss.fix(30_000))
        assertEquals(gnss.fix(10), other.fix(10))
    }

    @Test
    fun `a session on it is found as a loop, as Make a track from this session finds one`() {
        val loop = assertNotNull(findSessionLoop(fixes(5)))
        val off = abs(loop.lengthMeters - DemoCircuit.lengthMeters)
        assertTrue(off < DemoCircuit.lengthMeters * 0.05, "${loop.lengthMeters} m")
    }
}
