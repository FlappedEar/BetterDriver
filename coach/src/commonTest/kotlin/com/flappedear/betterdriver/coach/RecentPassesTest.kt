package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** FBBD-410: a corner cue measures from the corner's recent passes, not from the last one alone. */
class RecentPassesTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()

    private val target = CornerPass(
        corner = 1, lap = 1, brakeAt = 100.0, apexAt = 160.0, entryKmh = 160.0, minKmh = 80.0,
        timeMicros = 5_000_000L, gapMicros = 40_000, intervalMicros = 40_000,
    )
    private val corner = Corner(
        number = 1, brakeAt = 100.0, apexAt = 160.0, entryKmh = 160.0, minKmh = 80.0, turn = Turn.Right,
        entryAt = 130.0,
    )

    @Test
    fun `the usual pass is the median of each value, so one pass unlike the others moves none`() {
        val a = target.copy(lap = 2, brakeAt = 100.0, minKmh = 75.0, timeMicros = 5_200_000L, pickupAt = 180.0)
        val b = target.copy(lap = 3, brakeAt = 102.0, minKmh = 76.0, timeMicros = 5_250_000L, pickupAt = 182.0)
        // Braked 15 m early once.
        val c = target.copy(lap = 4, brakeAt = 85.0, minKmh = 74.0, timeMicros = 5_400_000L, pickupAt = null)
        val usual = typicalPass(listOf(a, b, c))
        assertEquals(100.0, usual.brakeAt)
        assertEquals(75.0, usual.minKmh)
        assertEquals(5_250_000L, usual.timeMicros)
        // Two of three know the throttle point: the middle of those two.
        assertEquals(181.0, usual.pickupAt)
        // The corner and the lap are the last pass's.
        assertEquals(4, usual.lap)
        assertSame(c, typicalPass(listOf(c)))
        // Of two passes, the middle.
        assertEquals(92.5, typicalPass(listOf(b.copy(brakeAt = 100.0), c)).brakeAt)
    }

    @Test
    fun `a value most passes lack is unknown`() {
        val a = target.copy(lap = 2, apexOffsetMeters = 1.0)
        val b = target.copy(lap = 3)
        val c = target.copy(lap = 4)
        assertNull(typicalPass(listOf(a, b, c)).apexOffsetMeters)
    }

    @Test
    fun `advice is asked for only where the usual pass and the last one both need it`() {
        val usual = target.copy(lap = 3, brakeAt = 100.0, minKmh = 74.0, timeMicros = 5_300_000L)
        // The last pass braked 15 m early by accident, and was slow at the apex like the others.
        val last = usual.copy(lap = 4, brakeAt = 85.0, minKmh = 73.0, timeMicros = 5_450_000L)
        assertTrue(cornerAdvice(last, target, corner).first() is Advice.BrakeLater)
        val asks = recentAdvice(usual, last, target, corner)
        assertEquals(listOf(Advice.CarrySpeed::class), asks.map { it::class })
        // The usual pass's numbers: 6 km/h more, to its 74 + 6.
        assertEquals(Advice.CarrySpeed(6, goalKmh = 80), asks.single())
        // He fixed the apex speed on the last lap: nothing left to ask, though the laps before needed it.
        val fixed = last.copy(brakeAt = 100.0, minKmh = 80.0)
        assertTrue(recentAdvice(usual, fixed, target, corner).isEmpty())
        // One pass: its own advice.
        assertEquals(cornerAdvice(last, target, corner), recentAdvice(last, last, target, corner))
    }

    @Test
    fun `a change already made leaves room for one still needed`() {
        val t = target.copy(apexOffsetMeters = 0.0, pickupAt = 170.0)
        // Usually: braked 15 m early, 3 m wide of the line, on the throttle 10 m late.
        val usual = t.copy(lap = 3, brakeAt = 85.0, apexOffsetMeters = 3.0, pickupAt = 180.0, timeMicros = 5_400_000L)
        assertEquals(2, cornerAdvice(usual, t, corner).size)
        // On the last lap he fixed the braking and the line; the throttle is still late.
        val last = t.copy(lap = 4, pickupAt = 180.0, timeMicros = 5_200_000L)
        assertEquals(listOf(Advice.Throttle::class), recentAdvice(usual, last, t, corner).map { it::class })
    }

    @Test
    fun `the same ask is the same change the same way, whatever its number`() {
        assertTrue(sameAsk(Advice.BrakeLater(10), Advice.BrakeLater(20, at = 90)))
        assertTrue(!sameAsk(Advice.BrakeLater(10), Advice.BrakeEarlier(10)))
        assertTrue(sameAsk(Advice.Line(2, turn = Turn.Left), Advice.Line(5, turn = Turn.Left)))
        assertTrue(!sameAsk(Advice.Line(2, turn = Turn.Left), Advice.Line(-2, turn = Turn.Left)))
        assertTrue(!sameAsk(Advice.Throttle(5), Advice.Throttle(-5)))
    }

    @Test
    fun `a one-off early brake among laps that brake right is not asked to brake later`() {
        // Corner 2 slow at its apex on every lap after the reference; on the last one it also braked 15 m early.
        val slow = SimCircuit.Pace(kmh = listOf(0.0, -8.0, 0.0, 0.0))
        val slowEarly = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0), kmh = listOf(0.0, -8.0, 0.0, 0.0))
        val laps = tracedLaps(track, sim.drive(listOf(steady, steady, steady)).fixes.asSequence())
        val reference = assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, learningLaps = 2), reference)
        val paces = listOf(slow, slow, slow, slowEarly, steady)
        val cues = sim.drive(paces).fixes.flatMap { engine.update(it).cues }
            .filter { it.kind == CueKind.Corner && it.corner == 2 }
        val said = cues.map { "${it.lap} ${it.text}" }
        // The cue after the one-off (lap numbers count the out-lap): the apex speed all of them lacked.
        val after = cues.last()
        assertEquals(paces.size + 1, after.lap, "cues: $said")
        assertTrue(after.advice is Advice.CarrySpeed, "cues: $said")
        assertTrue(cues.none { it.advice is Advice.BrakeLater }, "cues: $said")
        // Measured from the usual pass: braked where the laps before did, not 15 m early.
        val before = assertNotNull(after.before)
        assertEquals(paces.size, before.lap)
        assertTrue(abs(before.brakeAt - cues.first().before!!.brakeAt) < BrakeToleranceMeters, "cues: $said")
    }
}
