package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanMark
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.PlanPoint
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.feature.track.MapPedal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** The map screen shows the corner ahead, never the one just driven (UI-171 B, UI-172 C). */
class NextCornerFinderTest {
    // A made-up straight east along one latitude, two corners of 100 m each. Not a real place.
    private val lat = 10.0
    private val metresPerDegree = 111_320.0 * cos(lat * PI / 180)
    private fun lon(m: Double) = m / metresPerDegree

    private fun corner(name: String, from: Double, throttleAt: Double): PlanCorner {
        val line = (0..10).map { i ->
            val m = from + i * 10.0
            val pedal = when {
                m < from + 30 -> PedalState.Braking
                m < throttleAt -> PedalState.Coasting
                else -> PedalState.Accelerating
            }
            PlanPoint(m, lat, lon(m), pedal = pedal)
        }
        val pass = PlanPass(
            "s", "", 3, 90.0, 9.0,
            throttle = PlanMark(throttleAt, lat, lon(throttleAt)),
            line = line,
        )
        return PlanCorner(null, name, false, from, from + 100, 0.2, pass, pass, PlanAdvice())
    }

    private val plan = LinePlan(
        trackId = "t",
        sessionId = "s",
        sessionStartUtcMillis = 0,
        referenceLapNumber = 3,
        referenceLapSeconds = 90.0,
        laps = 5,
        sessions = 1,
        corners = listOf(corner("Corner 1", 0.0, 60.0), corner("Corners 2–3", 100.0, 160.0)),
    )

    @Test
    fun `shows the corner the car is in until it is back on throttle, then the next`() {
        val f = NextCornerFinder(plan)
        assertEquals("T1", f.at(lat, lon(30.0))?.label)
        assertEquals("T2–3", f.at(lat, lon(70.0))?.label)
        assertEquals("T2–3", f.at(lat, lon(120.0))?.label)
        // Past the last corner's throttle: the lap starts over with the first.
        assertEquals("T1", f.at(lat, lon(170.0))?.label)
    }

    @Test
    fun `off the line it keeps the corner without the car, and shows nothing before it was ever near`() {
        val f = NextCornerFinder(plan)
        assertNull(f.at(lat + 0.01, lon(30.0)))
        assertNull(f.at(null, null))
        assertNotNull(f.at(lat, lon(30.0))?.car)
        val off = f.at(lat + 0.01, lon(30.0))
        assertEquals("T1", off?.label)
        assertNull(off?.car)
    }

    @Test
    fun `the target is drawn by its pedals`() {
        val c = NextCornerFinder(plan).at(lat, lon(10.0))!!
        assertEquals(11, c.line.size)
        assertEquals(MapPedal.Brake, c.pedals.first())
        assertEquals(MapPedal.Coast, c.pedals[4])
        assertEquals(MapPedal.Throttle, c.pedals.last())
        // In metres from the plan's first point: 100 m east at the corner's end.
        assertEquals(100f, c.line.last().east, 0.5f)
        assertEquals(0f, c.line.last().north, 0.5f)
    }
}
