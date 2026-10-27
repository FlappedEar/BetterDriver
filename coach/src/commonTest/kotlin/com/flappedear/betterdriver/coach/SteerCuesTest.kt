@file:Suppress("MagicNumber", "LongParameterList")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.SteeringPass
import com.flappedear.betterdriver.timing.steering.wheelForBend
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the coach says about the hands on the FR-S (FBBD-321), as Arek picked on the UI review page,
 * round 54: why first, then what to do (UI-270 B), straightening with the throttle in one exit cue
 * (UI-271 B), a corner in pieces and the rear stepping out said too (UI-272 A).
 */
class SteerCuesTest {
    private val left =
        Corner(2, brakeAt = 100.0, apexAt = 160.0, entryKmh = 150.0, minKmh = 70.0, turn = Turn.Left, turnNumber = 6)

    private fun wheel(
        peak: Double = 90.0,
        unwindAt: Double? = 175.0,
        corrections: Int = 0,
        countersteer: Boolean = false,
        push: Double? = 40.0,
    ) = SteeringPass(peak, 160.0, 140.0, unwindAt, corrections, countersteer, push)

    /** A pass of [left], 0.2 s slower than [target] unless said. */
    private fun pass(
        steering: SteeringPass? = wheel(),
        offset: Double? = 2.0,
        pickup: Double? = 165.0,
        time: Long = 6_200_000,
        toApex: Long? = null,
        min: Double = 70.0,
    ) = CornerPass(
        2, 4, 100.0, 160.0, 150.0, min, time,
        apexOffsetMeters = offset, pickupAt = pickup, apexMicros = toApex, steering = steering,
    )

    private val target = pass(time = 6_000_000, toApex = 3_000_000)

    private fun steer(a: Advice) = assertIs<Advice.Steer>(a).what

    @Test
    fun `the words - why first - then what to do - in English and Polish`() {
        fun en(w: SteerAdvice, also: Advice? = null) = Phrases.corner(left, Advice.Steer(w), also)
        fun pl(w: SteerAdvice, also: Advice? = null) = PolishPhrases.corner(left, Advice.Steer(w), also)
        assertEquals("Turn 6, front slid, slower in", en(SteerAdvice.Less))
        assertEquals("Turn 6, ran wide, turn in more", en(SteerAdvice.More))
        assertEquals("Turn 6, one smooth turn", en(SteerAdvice.Smoother))
        assertEquals("Turn 6, rear stepped out, gentler throttle", en(SteerAdvice.Oversteer))
        val throttle = Advice.Throttle(5, afterApex = 5)
        assertEquals(
            "Turn 6, straighten 10 earlier, throttle 5 after the apex",
            en(SteerAdvice.OpenEarlier(10), throttle),
        )
        assertEquals("Szóstka, uciekł przód, wolniej na wejściu", pl(SteerAdvice.Less))
        assertEquals("Szóstka, szeroko, mocniej skręć", pl(SteerAdvice.More))
        assertEquals("Szóstka, jeden płynny ruch", pl(SteerAdvice.Smoother))
        assertEquals("Szóstka, uciekł tył, łagodniej z gazem", pl(SteerAdvice.Oversteer))
        assertEquals("Szóstka, prostuj 10 wcześniej, gaz 5 za apeksem", pl(SteerAdvice.OpenEarlier(10), throttle))
        // Below High confidence, without the number.
        val vague = Advice.Steer(SteerAdvice.OpenEarlier(10), precise = false)
        assertEquals("Turn 6, straighten earlier", Phrases.corner(left, vague))
        // The coach line, as on the mock-ups.
        assertEquals("Front slid · slower in", Phrases.line(Advice.Steer(SteerAdvice.Less)))
        assertEquals("Prostuj 10 m wcześniej", PolishPhrases.line(Advice.Steer(SteerAdvice.OpenEarlier(10))))
        // Every phrase has its clips, so pre-rendered voices say them.
        val all = listOf(
            SteerAdvice.Less,
            SteerAdvice.More,
            SteerAdvice.Smoother,
            SteerAdvice.Oversteer,
            SteerAdvice.OpenEarlier(20),
        )
        all.forEach {
            assertTrue(CueClips.covers(en(it, throttle)), en(it, throttle))
            assertTrue(CueClips.covers(pl(it, throttle), PolishPhrases), pl(it, throttle))
        }
    }

    @Test
    fun `the hands take the place of the advice they explain`() {
        // 30° more wheel beyond the bend's need than the target: the front slid. It takes the entry's
        // place (a slower apex would otherwise ask to carry more speed) and the line is left be.
        val slid = pass(steering = wheel(peak = 120.0, push = 70.0), offset = 0.0, min = 64.0)
        assertEquals(listOf<SteerAdvice>(SteerAdvice.Less), cornerAdvice(slid, target, left).map(::steer))
        // Wide with 20° less wheel: "ran wide, turn in more" instead of "tighter at the apex".
        val shy = pass(steering = wheel(peak = 70.0, push = 40.0), offset = -1.0)
        assertEquals(listOf<SteerAdvice>(SteerAdvice.More), cornerAdvice(shy, target, left).map(::steer))
        // Straightening late goes with the throttle cue (UI-271 B).
        val late = pass(steering = wheel(unwindAt = 187.0), pickup = 175.0)
        val exit = cornerAdvice(late, target, left)
        assertEquals(SteerAdvice.OpenEarlier(10), steer(exit[0]))
        assertIs<Advice.Throttle>(exit[1])
        // The rear stepped out: the whole exit is "gentler throttle", not "throttle earlier".
        val caught = pass(steering = wheel(countersteer = true), pickup = 175.0)
        assertEquals(listOf<SteerAdvice>(SteerAdvice.Oversteer), cornerAdvice(caught, target, left).map(::steer))
        // A corner in pieces is said when nothing else is at the apex.
        val sawing = pass(steering = wheel(corrections = 2))
        assertEquals(listOf<SteerAdvice>(SteerAdvice.Smoother), cornerAdvice(sawing, target, left).map(::steer))
        // Without the wheel on both passes, the advice is as before.
        val plain = pass(steering = null, offset = 0.0)
        assertEquals(listOf(Advice.Line(2, turn = Turn.Left)), cornerAdvice(plain, target, left))
        // A pass that did not lose time gets nothing.
        assertEquals(emptyList(), cornerAdvice(slid.copy(timeMicros = 6_000_000), target, left))
    }

    @Test
    fun `with more than two - the half that lost more keeps its own`() {
        // Front slid at the entry, wide with the line, late off lock and on throttle.
        val many = pass(
            steering = wheel(peak = 120.0, push = 70.0, unwindAt = 187.0), offset = 0.0, pickup = 175.0, min = 64.0,
        )
        // The entry lost more: the front slid there (no line, it slid), then the first of the exit.
        val lostBefore = many.copy(apexMicros = 3_150_000)
        assertEquals(
            listOf<SteerAdvice>(SteerAdvice.Less, SteerAdvice.OpenEarlier(10)),
            cornerAdvice(lostBefore, target, left).map(::steer),
        )
        // The exit lost more: straighten and throttle, one exit cue (UI-271 B).
        val lostAfter = many.copy(apexMicros = 3_050_000)
        val after = cornerAdvice(lostAfter, target, left)
        assertEquals(SteerAdvice.OpenEarlier(10), steer(after[0]))
        assertIs<Advice.Throttle>(after[1])
    }

    @Test
    fun `a cue about the hands is logged - read back - and judged by the next pass's wheel`() {
        val advice = Advice.Steer(SteerAdvice.OpenEarlier(10))
        val before = pass(steering = wheel(unwindAt = 187.0))
        val cue = Cue(
            3, 1_000, 5, CueKind.Corner, Phrases.corner(left, advice), 2, advice, before, target,
            turn = 6, also = Advice.Throttle(5, afterApex = 5),
        )
        val back = assertNotNull(CoachEvents.parseCue(cue.tMicros, CoachEvents.cueText(cue)))
        assertEquals(cue.advice, back.advice)
        assertEquals(cue.also, back.also)
        assertEquals(before.steering, back.before?.steering)
        for (w in listOf(SteerAdvice.Less, SteerAdvice.More, SteerAdvice.Smoother, SteerAdvice.Oversteer)) {
            val c = cue.copy(advice = Advice.Steer(w, precise = false), also = null)
            assertEquals(c.advice, CoachEvents.parseCue(0, CoachEvents.cueText(c))?.advice)
        }
        // Off lock 10 m earlier on the next pass, and on throttle 5 m earlier: both moved.
        val after = pass(steering = wheel(unwindAt = 177.0), pickup = 165.0)
        assertTrue(improved(advice, before, after))
        assertEquals(10.0, assertNotNull(movedBy(advice, before, after)), 1e-9)
        assertTrue(!improved(advice, before, before))
        assertTrue(!improved(advice, before, after.copy(steering = null)))
    }

    @Test
    fun `live - the wheel through a corner where the front slid gets the cue`() {
        val sim = SimCircuit()
        val track = sim.track()
        val steady = SimCircuit.Pace()
        // At the second corner the front slid: 6 km/h slower at the apex with 30° more lock on the same path.
        val slid = SimCircuit.Pace(kmh = listOf(0.0, -6.0, 0.0, 0.0))
        fun withWheel(drive: SimCircuit.Drive, paces: List<SimCircuit.Pace>) = drive.fixes.mapIndexed { i, f ->
            val s = drive.distance[i]
            // The bend of the path at s, from the compass heading a metre either side: left turns it back.
            var turn = sim.headingDeg(s + 1) - sim.headingDeg(s - 1)
            while (turn > 180) turn -= 360
            while (turn < -180) turn += 360
            val bend = -turn * PI / 180 / 2
            val pace = paces[(maxOf(s, 0.0) / sim.length).toInt().coerceIn(0, paces.size - 1)]
            val apex = sim.apexes[1]
            val d = ((s % sim.length) + sim.length) % sim.length
            val extra = if (pace === slid && kotlin.math.abs(d - apex) <= 20.0) 30.0 else 0.0
            f.copy(steerDeg = wheelForBend(bend) + 10.0 + extra)
        }
        val earlier = List(3) { steady }
        val reference = assertNotNull(
            buildReference(
                track,
                listOf(SessionLaps("earlier", tracedLaps(track, withWheel(sim.drive(earlier), earlier).asSequence()))),
            ),
        )
        val paces = listOf(slid, slid, steady, steady)
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, learningLaps = 2), reference)
        val cues = withWheel(sim.drive(paces), paces).flatMap { engine.update(it).cues }
        val cue = cues.first { it.kind == CueKind.Corner }
        assertEquals(Advice.Steer(SteerAdvice.Less), cue.advice?.withPrecision(true), cue.text)
        assertTrue(cue.text.endsWith("front slid, slower in"), cue.text)
    }
}
