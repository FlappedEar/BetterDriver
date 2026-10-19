@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Corner cues say the target and the change (FBBD-242, Arek's picks UI-87 C, UI-88 C, UI-89 A). */
class TargetCuesTest {
    /** Turn 3: the corner starts 200 m along, its apex is at 260 m. */
    private val corner = Corner(2, 120.0, 260.0, 150.0, 60.0, Turn.Left, "c3", 3, entryAt = 200.0)
    private val target = CornerPass(2, 1, brakeAt = 100.0, apexAt = 260.0, entryKmh = 150.0, minKmh = 62.0, timeMicros = 5_000_000)

    private fun pass(brakeAt: Double = 100.0, minKmh: Double = 62.0) =
        target.copy(lap = 4, timeMicros = 5_300_000, brakeAt = brakeAt, minKmh = minKmh)

    @Test
    fun `the brake point is counted to the corner's start`() {
        // Braked at 85 m along, 15 m early: asked for 100 m along, 100 m before the corner's start.
        assertEquals(Advice.BrakeLater(15, at = 100), advise(pass(brakeAt = 85.0), target, corner))
        // 15 m late, slower at the apex: asked for 100 m along again.
        assertEquals(Advice.BrakeEarlier(15, at = 100), advise(pass(brakeAt = 115.0, minKmh = 56.0), target, corner))
        assertEquals(Advice.CarrySpeed(4, goalKmh = 61), advise(pass(minKmh = 57.0), target, corner))
        // Without the corner, as before: the change only.
        assertEquals(Advice.BrakeLater(15), advise(pass(brakeAt = 85.0), target))
    }

    @Test
    fun `without a corner start, or braking past it, the apex is the mark`() {
        assertEquals(Advice.BrakeLater(15, at = 160), advise(pass(brakeAt = 85.0), target, corner.copy(entryAt = null)))
        // A brake point at or past the corner's start would be "at 0" or less: counted to the apex.
        val late = corner.copy(entryAt = 102.0)
        assertEquals(260.0, brakeAnchor(late, 100.0))
        assertEquals(200.0, brakeAnchor(corner, 100.0))
    }

    @Test
    fun `cues say the target, then the change`() {
        assertEquals("Turn 3, brake at 100, 10 later", Phrases.corner(corner, Advice.BrakeLater(10, at = 100)))
        assertEquals("Turn 3, brake at 120, 10 earlier", Phrases.corner(corner, Advice.BrakeEarlier(10, at = 120)))
        assertEquals("Turn 3, 60 at the apex, 5 more", Phrases.corner(corner, Advice.CarrySpeed(5, goalKmh = 60)))
        // Below High confidence, no numbers, as before (FBBD-177).
        assertEquals("Turn 3, brake a little later", Phrases.corner(corner, Advice.BrakeLater(10, false, 100)))
        assertEquals("Turn 3, brake earlier", Phrases.corner(corner, Advice.BrakeEarlier(10, false, 120)))
        assertEquals("Turn 3, carry a little more speed", Phrases.corner(corner, Advice.CarrySpeed(5, false, 60)))
        // Logged before FBBD-242, without a target.
        assertEquals("Turn 3, brake 10 metres later", Phrases.corner(corner, Advice.BrakeLater(10)))
        assertEquals("Turn 3, carry 5 more", Phrases.corner(corner, Advice.CarrySpeed(5)))
    }

    @Test
    fun `the coach line shows the target with its unit`() {
        assertEquals("Brake at 100 m · 10 later", Phrases.line(Advice.BrakeLater(10, at = 100)))
        assertEquals("Brake at 120 m · 10 earlier", Phrases.line(Advice.BrakeEarlier(10, at = 120)))
        assertEquals("60 km/h at the apex · 5 more", Phrases.line(Advice.CarrySpeed(5, goalKmh = 60)))
        assertNull(Phrases.line(Advice.BrakeLater(10, false, 100)))
        assertNull(Phrases.line(Advice.CarrySpeed(5)))
    }

    @Test
    fun `targets are logged and read back, and old cues still read`() {
        for (a in listOf(
            Advice.BrakeLater(10, at = 100),
            Advice.BrakeLater(10, false, 100),
            Advice.BrakeEarlier(15, at = 120),
            Advice.CarrySpeed(5, goalKmh = 60),
            Advice.CarrySpeed(5, false, 60),
        )) {
            val cue = Cue(3, 1_000, 4, CueKind.Corner, Phrases.corner(corner, a), 2, a, pass(), target, turn = 3)
            assertEquals(a, assertNotNull(CoachEvents.parseCue(1_000, CoachEvents.cueText(cue))).advice)
        }
        for ((logged, advice) in listOf(
            "brake_later:10" to Advice.BrakeLater(10),
            "brake_later:10:vague" to Advice.BrakeLater(10, precise = false),
            "brake_earlier" to Advice.BrakeEarlier(),
            "carry:5" to Advice.CarrySpeed(5),
        )) {
            val read = CoachEvents.parseCue(0, "seq=1 lap=4 kind=Corner corner=2 advice=$logged text=x")
            assertEquals(advice, assertNotNull(read).advice, logged)
        }
    }

    private fun reviewed(advice: Advice, after: CornerPass, before: CornerPass = pass()): String? {
        val cue = Cue(7, 0, 4, CueKind.Corner, Phrases.corner(corner, advice), 2, advice, before, target, turn = 3)
        val r = CueResult(1, cue, after, true, 120_000, Confidence.HIGH)
        val events = listOf(
            RecorderEvent(0, CoachEvents.CUE, CoachEvents.cueText(cue)),
            RecorderEvent(1, CoachEvents.RESULT, CoachEvents.resultText(r)),
        )
        return CueReview.of(events).cues.single().outcome()
    }

    @Test
    fun `the review measures against the target`() {
        // Asked 100 m before the corner from 85 m along: the corner starts at 200 m. Braked at 93 m along.
        val brake = reviewed(Advice.BrakeLater(15, at = 100), pass(brakeAt = 93.0), pass(brakeAt = 85.0))
        assertEquals("Braked at 107 m, 8 m later than before · 0.12 s gained", brake)
        val speed = reviewed(Advice.CarrySpeed(5, goalKmh = 62), pass(minKmh = 60.4), pass(minKmh = 57.0))
        assertEquals("Apex 60 km/h, 3 more than before · 0.12 s gained", speed)
        // Not High: the words without numbers, as before.
        val vague = reviewed(Advice.BrakeLater(15, false, 100), pass(brakeAt = 93.0), pass(brakeAt = 85.0))
        assertEquals("Braked later, short of best · time gained", vague)
    }
}
