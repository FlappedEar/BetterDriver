package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.map.GgPoint
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapUiTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val gain = 0xFF00FF00.toInt()
    private val loss = 0xFFFF0000.toInt()
    private val grey = 0xFF808080.toInt()

    @Test
    fun `colours mix channel by channel`() {
        assertEquals(black, lerpColor(black, white, 0.0))
        assertEquals(white, lerpColor(black, white, 1.0))
        assertEquals(0xFF808080.toInt(), lerpColor(black, white, 0.5))
        assertEquals(white, lerpColor(black, white, 7.0))
    }

    @Test
    fun `the speed scale runs from its first to its last stop`() {
        assertEquals(SpeedScaleColors.first(), scaleColor(0.0))
        assertEquals(SpeedScaleColors.last(), scaleColor(1.0))
        assertEquals(SpeedScaleColors[3], scaleColor(0.5))
        assertEquals(SpeedScaleColors.last(), scaleColor(2.0))
    }

    @Test
    fun `losing time is the loss colour, gaining the gain colour, none neutral`() {
        assertEquals(loss, gainLossColor(0.3, 0.2, gain, grey, loss))
        assertEquals(gain, gainLossColor(-0.2, 0.2, gain, grey, loss))
        assertEquals(grey, gainLossColor(0.0, 0.2, gain, grey, loss))
        assertEquals(lerpColor(grey, loss, 0.5), gainLossColor(0.1, 0.2, gain, grey, loss))
        assertEquals(grey, gainLossColor(Double.NaN, 0.2, gain, grey, loss))
    }

    @Test
    fun `the speed scale is widened to whole tens`() {
        assertEquals(ScaleRange(60.0, 180.0), speedRange(listOf(63.2, 171.0, 120.0)))
        assertEquals(ScaleRange(80.0, 90.0), speedRange(listOf(80.0)))
        assertNull(speedRange(emptyList()))
        assertEquals(0.25, ScaleRange(60.0, 100.0).fraction(70.0), 1e-12)
    }

    @Test
    fun `the gain and loss scale ignores a spike`() {
        val rates = List(95) { 0.04 * if (it % 2 == 0) 1 else -1 } + List(5) { 3.0 }
        assertEquals(0.05, gainLossLimit(rates), 1e-12)
        assertEquals(0.2, gainLossLimit(listOf(0.15, -0.18)), 1e-12)
        assertEquals(0.05, gainLossLimit(emptyList()), 1e-12)
    }

    @Test
    fun `corners are tagged T and their number`() {
        assertEquals("T1", cornerTag("Corner 1"))
        assertEquals("T2–3", cornerTag("Corners 2–3"))
        assertEquals("Hairpin", cornerTag("Hairpin"))
    }

    @Test
    fun `the frame fits the map north up, centred, and inverts`() {
        val frame = MapFrame(0.0..200.0, 0.0..100.0, width = 400f, height = 400f, padding = 0f)
        assertEquals(2.0, frame.scale, 1e-9)
        assertEquals(0f, frame.x(0.0), 1e-4f)
        assertEquals(400f, frame.x(200.0), 1e-4f)
        // North is up: the top edge of the extent is above its bottom, both centred vertically.
        assertEquals(100f, frame.y(100.0), 1e-4f)
        assertEquals(300f, frame.y(0.0), 1e-4f)
        assertEquals(50.0, frame.east(frame.x(50.0)), 1e-6)
        assertEquals(25.0, frame.north(frame.y(25.0)), 1e-6)
    }

    @Test
    fun `the extent covers the outline and the line`() {
        val map = LapMap(
            0, null, 100.0,
            listOf(listOf(MapPoint(-5.0, 2.0, 0.0, null, null, null))),
            listOf(MetricPoint(0.0, 0.0), MetricPoint(10.0, 20.0)),
            emptyList(),
        )
        val (east, north) = mapExtent(map)
        assertEquals(-5.0..10.0, east)
        assertEquals(0.0..20.0, north)
        // 15 m wide and 20 m tall: as tall as allowed.
        assertEquals(0.8f, mapAspect(map), 1e-6f)
    }

    @Test
    fun `rings are every half g to just past the largest reading, at least 1 g`() {
        assertEquals(listOf(0.5, 1.0), ggRings(0.4))
        assertEquals(listOf(0.5, 1.0, 1.5), ggRings(1.2))
        assertEquals(6, ggRings(9.0).size)
    }

    @Test
    fun `the G-G cursor finds the reading at a distance`() {
        val trace = GgTrace(
            GgSource.Gps,
            listOf(GgPoint(0.1, 0.0, 10.0), GgPoint(0.2, 0.0, 30.0), GgPoint(0.3, 0.0, null)),
        )
        assertEquals(0.2, ggPointAt(trace, 27.0)!!.lateralG, 1e-12)
        assertNull(ggPointAt(trace, 60.0))
        assertEquals(0.3, maxG(trace, null), 1e-12)
    }

    @Test
    fun `peaks are the hardest braking, cornering and speeding up`() {
        val points = listOf(GgPoint(-1.1, -0.2, 0.0), GgPoint(0.4, -0.9, 1.0), GgPoint(0.0, 0.3, 2.0))
        val trace = GgTrace(GgSource.Gps, points)
        assertEquals(GgPeaks(0.9, 1.1, 0.3), ggPeaks(trace))
    }
}
