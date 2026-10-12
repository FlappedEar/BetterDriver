@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PassesTest {
    /** A 1 km straight driven at 1 m steps; [kmh] gives the speed at each metre. */
    private fun trace(kmh: (Int) -> Double, y: (Int) -> Double = { 0.0 }): LapTrace {
        val n = 1_000
        var t = 0.0
        val times = LongArray(n + 1)
        for (i in 1..n) {
            t += 3.6 / kmh(i) * 1_000_000
            times[i] = t.toLong()
        }
        return LapTrace(List(n + 1) { Xy(it.toDouble(), y(it)) }, times.toList(), List(n + 1) { kmh(it) })
    }

    /** Slows from 150 to [min] km/h around [apex], braking from [brake]. */
    private fun dip(at: Int, brake: Int, apex: Int, min: Double): Double = when {
        at < brake || at > apex + 40 -> 150.0
        at <= apex -> 150.0 - (150.0 - min) * (at - brake) / (apex - brake)
        else -> min + (150.0 - min) * (at - apex) / 40.0
    }

    private val speed = { i: Int -> minOf(dip(i, 30, 70, 80.0), dip(i, 900, 975, 70.0)) }
    private val path = trace(speed)
    private val corners = listOf(
        Corner(1, brakeAt = 30.0, apexAt = 70.0, entryKmh = 150.0, minKmh = 80.0, turn = Turn.Left),
        Corner(2, brakeAt = 900.0, apexAt = 975.0, entryKmh = 150.0, minKmh = 70.0, turn = Turn.Left),
    )
    private val map = CornerMap(path, corners)

    @Test
    fun `corners whose windows start and end at the line are measured`() {
        assertEquals(0.0, map.windows[0].start)
        assertEquals(path.lengthMeters, map.windows[1].endInclusive)
        val passes = map.passes(path, 1).associateBy { it.corner }
        assertEquals(setOf(1, 2), passes.keys)
        assertEquals(80.0, assertNotNull(passes[1]).minKmh, 1.0)
        assertEquals(70.0, assertNotNull(passes[2]).minKmh, 1.0)
    }

    @Test
    fun `a pass with the car off the path inside its window is not measured`() {
        // 50 m to the side for 10 m before turn 2's apex, then back on the line.
        val off = trace(speed) { i -> if (i in 950..960) 50.0 else 0.0 }
        val passes = map.passes(off, 1).associateBy { it.corner }
        assertNotNull(passes[1])
        assertNull(passes[2])
    }

    @Test
    fun `live, a window that ends at the line closes at the crossing time`() {
        val window = map.windows[1]
        val meter = PassMeter(corners[1], window, 1)
        // Positions every 10 m, 1 s apart, up to 990 m; the line is crossed 1 s after the last one.
        var t = 0L
        var along = window.start - 10.0
        while (along <= 990.0) {
            assertNull(meter.add(along, t, 100.0))
            along += 10.0
            t += 1_000_000
        }
        val lastT = t - 1_000_000
        val pass = assertNotNull(meter.finish(lineMicros = lastT + 1_000_000, lineKmh = 100.0))
        // The window was entered at the second position, 1 s in.
        assertEquals(lastT + 1_000_000 - 1_000_000, pass.timeMicros)
        assertNull(meter.finish(lineMicros = lastT + 2_000_000))
    }

    @Test
    fun `live, a window that ends short of the line closes at its own end`() {
        // The window ends at 980 m; the line is at 1000 m, crossed 2 s after the last position at 975 m.
        val meter = PassMeter(corners[1], 900.0..980.0, 1, lineAt = 1000.0)
        var t = 0L
        var along = 885.0
        while (along <= 975.0) {
            assertNull(meter.add(along, t, 100.0))
            along += 10.0
            t += 1_000_000
        }
        val lastT = t - 1_000_000
        val pass = assertNotNull(meter.finish(lineMicros = lastT + 2_500_000, lineKmh = 100.0))
        // Entered at 900 m (1.5 s); left at 980 m, 5 m of the 25 m to the line, so 0.5 s after 975 m (9.5 s).
        assertEquals(8_000_000L, pass.timeMicros)
    }

    @Test
    fun `live, a window that starts at the line opens at the crossing time`() {
        val window = map.windows[0]
        val meter = PassMeter(corners[0], window, 2)
        // The line was crossed at 0 s; the first position, 10 m on, is 1 s later.
        meter.start(lineMicros = 0, lineKmh = 100.0)
        var t = 1_000_000L
        var along = 10.0
        var pass: CornerPass? = null
        while (pass == null) {
            pass = meter.add(along, t, 100.0)
            along += 10.0
            t += 1_000_000
        }
        // The window ends at 130 m, reached at 13 s, measured from the line.
        assertEquals(window.endInclusive, 130.0)
        assertEquals(13_000_000L, pass.timeMicros)
    }
}
