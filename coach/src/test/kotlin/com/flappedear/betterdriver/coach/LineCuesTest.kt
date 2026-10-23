@file:Suppress("MagicNumber", "LongParameterList")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the coach says and shows about the line and the throttle (FBBD-307), as Arek picked on the UI
 * review page, round 43: UI-168 B, UI-169 C, UI-170 B, UI-173 A and UI-174 A.
 */
class LineCuesTest {
    private val left =
        Corner(2, brakeAt = 100.0, apexAt = 160.0, entryKmh = 150.0, minKmh = 70.0, turn = Turn.Left, turnNumber = 1)

    /** A pass of [left], 0.2 s slower than [target] unless said; times to the apex as given. */
    private fun pass(
        offset: Double? = 0.0,
        pickup: Double? = 170.0,
        time: Long = 6_200_000,
        toApex: Long? = null,
        brake: Double = 100.0,
        min: Double = 70.0,
    ) = CornerPass(
        2, 4, brake, 160.0, 150.0, min, time, apexOffsetMeters = offset, pickupAt = pickup, apexMicros = toApex,
    )

    private val target = pass(offset = 2.0, pickup = 165.0, time = 6_000_000, toApex = 3_000_000)

    @Test
    fun `the line is said without its number and the throttle by its target only`() {
        assertEquals("Turn 1, tighter at the apex", Phrases.corner(left, Advice.Line(2)))
        assertEquals("Turn 1, wider at the apex", Phrases.corner(left, Advice.Line(-3)))
        assertEquals("Turn 1, throttle 5 after the apex", Phrases.corner(left, Advice.Throttle(5, afterApex = 5)))
        assertEquals("Turn 1, throttle 5 before the apex", Phrases.corner(left, Advice.Throttle(5, afterApex = -5)))
        assertEquals("Turn 1, throttle at the apex", Phrases.corner(left, Advice.Throttle(5, afterApex = 0)))
        assertEquals("Turn 1, throttle earlier", Phrases.corner(left, Advice.Throttle(5, false, 5)))
        assertEquals("Jedynka, ciaśniej w apeksie", PolishPhrases.corner(left, Advice.Line(2)))
        assertEquals("Jedynka, szerzej w apeksie", PolishPhrases.corner(left, Advice.Line(-2)))
        assertEquals("Jedynka, gaz 5 za apeksem", PolishPhrases.corner(left, Advice.Throttle(5, afterApex = 5)))
        assertEquals("Jedynka, gaz później", PolishPhrases.corner(left, Advice.Throttle(-5, false, 5)))
    }

    @Test
    fun `two things in one cue, and its short form says the first alone`() {
        val text = Phrases.corner(left, Advice.Line(2), Advice.Throttle(5, afterApex = 5))
        assertEquals("Turn 1, tighter at the apex, throttle 5 after the apex", text)
        assertEquals(
            "Jedynka, ciaśniej w apeksie, gaz 5 za apeksem",
            PolishPhrases.corner(left, Advice.Line(2), Advice.Throttle(5, afterApex = 5)),
        )
        val cue =
            Cue(1, 0, 5, CueKind.Corner, text, 2, Advice.Line(2), turn = 1, also = Advice.Throttle(5, afterApex = 5))
        assertEquals("Turn 1, tighter at the apex", Phrases.shortCorner(cue))
        val braking = cue.copy(
            text = "Turn 1, brake at 95, 10 later, tighter at the apex",
            advice = Advice.BrakeLater(10, at = 95),
            also = Advice.Line(2),
        )
        assertEquals("Turn 1, brake at 95", Phrases.shortCorner(braking))
        // Every word of them has a clip, so pre-rendered voices say them too.
        assertTrue(CueClips.covers(text))
        val polish = PolishPhrases.corner(left, Advice.Line(-2), Advice.Throttle(5, afterApex = -5))
        assertTrue(CueClips.covers(polish, PolishPhrases))
    }

    @Test
    fun `with three things to change the line stays and the half that lost more picks the other`() {
        // Braked 15 m early, 2 m wide at the apex, throttle 5 m late.
        val lostBefore = pass(offset = 0.0, pickup = 170.0, time = 6_300_000, toApex = 3_250_000, brake = 85.0)
        assertEquals(listOf("brake_later", "line"), kinds(cornerAdvice(lostBefore, target, left)))
        val lostAfter = pass(offset = 0.0, pickup = 170.0, time = 6_300_000, toApex = 3_050_000, brake = 85.0)
        assertEquals(listOf("line", "throttle"), kinds(cornerAdvice(lostAfter, target, left)))
        // Without the time to the apex, the first two as they are driven.
        val unknown = lostBefore.copy(apexMicros = null)
        assertEquals(listOf("brake_later", "line"), kinds(cornerAdvice(unknown, target, left)))
        // Two or fewer are all said, in driving order.
        assertEquals(listOf("line", "throttle"), kinds(cornerAdvice(pass(), target, left)))
        val throttle = cornerAdvice(pass(), target, left).last() as Advice.Throttle
        assertEquals(5, throttle.earlierMeters)
        assertEquals(5, throttle.afterApex)
    }

    private fun kinds(a: List<Advice>) = a.map {
        when (it) {
            is Advice.BrakeLater -> "brake_later"
            is Advice.BrakeEarlier -> "brake_earlier"
            is Advice.CarrySpeed -> "carry"
            is Advice.Line -> "line"
            is Advice.Throttle -> "throttle"
        }
    }

    @Test
    fun `a cue of two worked only when both moved`() {
        val before = pass()
        val cue = Cue(
            1, 0, 5, CueKind.Corner, "", 2, Advice.Line(2, turn = Turn.Left), before, target,
            also = Advice.Throttle(5, afterApex = 5),
        )
        assertTrue(improved(cue, before, pass(offset = 1.5, pickup = 166.0)))
        assertFalse(improved(cue, before, pass(offset = 1.5, pickup = 170.0)))
        assertFalse(improved(cue, before, pass(offset = 0.5, pickup = 166.0)))
    }

    @Test
    fun `the coach line shows what came of a cue`() {
        val line = Advice.Line(2, turn = Turn.Left)
        val worked = listOf(Moved(line, 1.8))
        assertEquals("Apex 1.8 m tighter ✓ · −0.09 s", Phrases.result(worked, true, 0.09))
        assertEquals("Apex 0.4 m tighter · not yet", Phrases.result(listOf(Moved(line, 0.4)), false, 0.01))
        assertEquals("Apeks 1,8 m ciaśniej ✓ · −0,09 s", PolishPhrases.result(worked, true, 0.09))
        assertEquals("Tighter at the apex ✓", Phrases.result(listOf(Moved(line, null)), true, null))
        assertEquals("Apex 1.8 m tighter ✓", Phrases.result(worked, true, null))
        val two = listOf(Moved(line, 1.8), Moved(Advice.Throttle(5, afterApex = 5), 4.0))
        assertEquals("Apex 1.8 m tighter · Throttle 4 m earlier ✓ · +0.02 s", Phrases.result(two, true, -0.02))
    }

    @Test
    fun `the coach line keeps what came of a cue while the voice says it worked`() {
        val before = pass()
        val cue = Cue(
            3, 0, 5, CueKind.Corner, "Turn 1, tighter at the apex", 2, Advice.Line(2, turn = Turn.Left), before, target,
            turn = 1,
        )
        val after = pass(offset = 1.8, time = 6_110_000)
        val r = CueResult(0, cue, after, improved(cue, before, after), 90_000, Confidence.HIGH)
        val shown = CoachLines(Phrases).result(r)
        assertEquals(CoachStatus("T1", "Apex 1.8 m tighter ✓ · −0.09 s", praise = true), shown)
        val praise = Cue(4, 0, 6, CueKind.Praise, "Turn 1, that worked", 2, turn = 1, praise = Praise.Worked)
        assertEquals(shown, CoachLines(Phrases).cue(praise, shown, null))
        val notYet = r.copy(after = pass(offset = 0.4), improved = false, gainMicros = 10_000)
        assertEquals(CoachStatus("T1", "Apex 0.4 m tighter · not yet", dim = true), CoachLines(Phrases).result(notYet))
        // A Medium cue's result has no numbers.
        val vague = CoachLines(Phrases).result(r.copy(cue = cue.copy(confidence = Confidence.MEDIUM)))
        assertEquals("Tighter at the apex ✓", vague.text)
    }

    @Test
    fun `the out-lap says the corners the session works on`() {
        assertEquals("Today, turns 9, 1 and 4", Phrases.plan(listOf(9, 1, 4)))
        assertEquals("Today, Turn 9", Phrases.plan(listOf(9)))
        assertEquals("Dzisiaj, Dziewiątka, Jedynka, Czwórka", PolishPhrases.plan(listOf(9, 1, 4)))
        assertEquals("Today: T9 · T1 · T4 · ≈ 1.0 s to find", Phrases.planLine(listOf(9, 1, 4), 0.98))
        assertEquals("Dzisiaj: T9 · T1 · T4 · ≈ 1,0 s do znalezienia", PolishPhrases.planLine(listOf(9, 1, 4), 0.98))
        assertEquals("Today: T9 · T1", Phrases.planLine(listOf(9, 1), null))
        assertTrue(CueClips.covers(Phrases.plan(listOf(9, 1, 4))))
        assertTrue(CueClips.covers(PolishPhrases.plan(listOf(9, 1, 4)), PolishPhrases))
        val plan = Cue(1, 0, 1, CueKind.Plan, Phrases.plan(listOf(9, 1, 4)), turns = listOf(9, 1, 4))
        assertEquals(
            CoachStatus(null, "Today: T9 · T1 · T4 · ≈ 1.0 s to find"),
            CoachLines(Phrases).cue(plan, null, 0.98),
        )
    }

    @Test
    fun `line, throttle, two in one and plan cues are logged and read back`() {
        val cue = Cue(
            7, 1_000, 5, CueKind.Corner, "Turn 1, tighter at the apex, throttle 5 after the apex", 2,
            Advice.Line(2, turn = Turn.Left), pass(toApex = 3_100_000), target, turn = 1, cornerId = "c1",
            also = Advice.Throttle(5, afterApex = 5),
        )
        val back = assertNotNull(CoachEvents.parseCue(1_000, CoachEvents.cueText(cue)))
        assertEquals(cue.advice, back.advice)
        assertEquals(cue.also, back.also)
        assertEquals(cue.before, back.before)
        assertEquals(cue.target, back.target)
        val plan = Cue(1, 10, 1, CueKind.Plan, "Today, turns 9, 1 and 4", turns = listOf(9, 1, 4))
        assertEquals(plan, CoachEvents.parseCue(10, CoachEvents.cueText(plan)))
        // A pass logged before FBBD-307 still reads.
        val old =
            "seq=1 lap=4 kind=Corner corner=2 advice=brake_later:10 before=4,100.0,160.0,150.0,70.0,6200000 text=x"
        assertEquals(null, CoachEvents.parseCue(0, old)?.before?.apexOffsetMeters)
        assertEquals(100.0, CoachEvents.parseCue(0, old)?.before?.brakeAt)
        assertEquals(
            "source=s1 corners=c9,c1,c4 gain_s=0.98",
            CoachEvents.planText("s1", listOf("c9", "c1", "c4"), 0.98),
        )
        // A session coached from a line plan links back to the plan's session (coaching history).
        val events = listOf(RecorderEvent(5, CoachEvents.PLAN, CoachEvents.planText("s1", listOf("c9"), 0.4)))
        assertEquals("s1", CoachEvents.focusSource(events))
        // The plan has no pass to review: the cue review leaves it out.
        val said = RecorderEvent(10, CoachEvents.CUE, CoachEvents.cueText(plan))
        assertTrue(CueReview.of(listOf(said)).cues.isEmpty())
    }
}
