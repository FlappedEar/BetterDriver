@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Gate
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-57: line crossings with interpolation and a direction check. */
class CrossingTest {
    private val origin = GeoPoint(52.0, 21.0)
    private val frame = LocalFrame(origin)

    /** A line across x = 0 from y = -15 to 15, for a car driving east. */
    private val eastbound = Gate(TrackLine(frame.toGeo(Xy(0.0, 15.0)), frame.toGeo(Xy(0.0, -15.0))), frame)

    @Test
    fun `interpolates the crossing time`() {
        val c = assertNotNull(eastbound.crossing(Xy(-1.0, 0.0), 0, Xy(3.0, 0.0), 40_000))
        assertEquals(10_000.0, c.tMicros, 1.0)
        assertEquals(0.0, c.at.x, 1e-6)
    }

    @Test
    fun `ignores the wrong direction`() {
        assertNull(eastbound.crossing(Xy(3.0, 0.0), 0, Xy(-1.0, 0.0), 40_000))
        assertNotNull(eastbound.flippedGate().crossing(Xy(3.0, 0.0), 0, Xy(-1.0, 0.0), 40_000))
    }

    @Test
    fun `ignores passing beside the line`() {
        assertNull(eastbound.crossing(Xy(-1.0, 20.0), 0, Xy(3.0, 20.0), 40_000))
        assertNull(eastbound.crossing(Xy(-1.0, -16.0), 0, Xy(3.0, -16.0), 40_000))
    }

    @Test
    fun `a fix on the line counts once`() {
        val before = eastbound.crossing(Xy(-2.0, 0.0), 0, Xy(0.0, 0.0), 40_000)
        val after = eastbound.crossing(Xy(0.0, 0.0), 40_000, Xy(2.0, 0.0), 80_000)
        assertNull(before)
        assertEquals(40_000.0, assertNotNull(after).tMicros, 1.0)
    }

    @Test
    fun `line taken from a heading is crossed forwards on that heading`() {
        for (heading in listOf(0.0, 37.0, 90.0, 181.0, 270.0, 333.0)) {
            val line = TrackLine.across(origin, heading, 20.0)
            val g = Gate(line, frame)
            val h = Math.toRadians(heading)
            val dir = Xy(kotlin.math.sin(h), kotlin.math.cos(h))
            assertNotNull(g.crossing(dir * -2.0, 0, dir * 2.0, 1_000), "heading $heading forwards")
            assertNull(g.crossing(dir * 2.0, 0, dir * -2.0, 1_000), "heading $heading backwards")
        }
    }

    @Test
    fun `crossing times on 25 Hz data are within 5 ms of the truth`() {
        val circuit = FakeCircuit()
        val drive = circuit.drive(paces = listOf(1.0, 1.08, 0.93, 1.02))
        val timer = LapTimer(circuit.track())
        val measured = mutableMapOf<Int, MutableList<Long>>()
        for (fix in drive.fixes) {
            for (e in timer.update(fix)) {
                if (e is LapEvent.SectorDone) measured.getOrPut((e.index + 1) % 3) { mutableListOf() } += e.tMicros
            }
        }
        val marks = listOf(0.0, 1.0 / 3, 2.0 / 3)
        var worst = 0.0
        for ((i, m) in marks.withIndex()) {
            val truth = drive.lineTimesMicros.getValue(m)
            val got = measured.getValue(i)
            // Every start/finish pass ends the last sector, the out lap's included.
            val expected = truth
            assertEquals(expected.size, got.size, "passes of mark $m")
            expected.zip(got).forEach { (want, have) -> worst = maxOf(worst, abs(want - have)) }
        }
        assertTrue(worst < 5_000, "worst crossing error ${worst / 1000} ms")
    }

    private fun Gate.flippedGate() = Gate(TrackLine(frame.toGeo(Xy(0.0, -15.0)), frame.toGeo(Xy(0.0, 15.0))), frame)
}
