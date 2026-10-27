@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.gear.GearPull
import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.gear.PullPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gear in the corner (FBBD-326), as Arek picked on the UI review page, round 56: the gear said
 * with the brake cue (UI-279 A, "Siódemka, hamuj na 80, dwójka"), driven in M with the paddles (UI-278 A).
 */
class GearCuesTest {
    private val left =
        Corner(2, brakeAt = 100.0, apexAt = 160.0, entryKmh = 150.0, minKmh = 70.0, turn = Turn.Left, turnNumber = 7)

    // The FR-S's 2nd and 3rd: about 72 and 49 rpm per km/h.
    private val gearbox = Gearbox(
        "FR-S 6AT",
        listOf(
            GearPull(2, 260.0, listOf(PullPoint(20.0, 2.5, 50))),
            GearPull(3, 177.0, listOf(PullPoint(20.0, 1.7, 50))),
        ),
        maxRpm = 7200.0,
    )

    /** A pass of [left] at 72 km/h, 0.2 s slower than the target unless said, at [rpm] at the slowest point. */
    private fun pass(rpm: Double?, time: Long = 6_200_000, brakeAt: Double = 100.0, gear: Int? = null) = CornerPass(
        2, 4, brakeAt, 160.0, 150.0, 72.0, time, apexRpm = rpm, plannedGear = gear,
    )

    private val inThird = 72 / 3.6 * 177.0
    private val inSecond = 72 / 3.6 * 260.0

    @Test
    fun `the gear is said with the brake cue - in English and Polish`() {
        val brake = Advice.BrakeLater(10, at = 80)
        assertEquals("Turn 7, brake at 80, 10 later, second", Phrases.corner(left, brake, Advice.Gear(2)))
        assertEquals("Turn 7, second", Phrases.corner(left, Advice.Gear(2)))
        assertEquals("Second gear", Phrases.line(Advice.Gear(2)))
        assertEquals("Dwójka", PolishPhrases.line(Advice.Gear(2)))
        val pl = PolishPhrases.corner(left, brake, Advice.Gear(2))
        assertTrue(pl.startsWith("Siódemka, hamuj na 80"), pl)
        assertTrue(pl.endsWith(", dwójka"), pl)
        // Pre-rendered voices have every gear.
        (1..6).forEach { g ->
            val en = Phrases.corner(left, brake, Advice.Gear(g))
            assertTrue(CueClips.covers(en), en)
            val p = PolishPhrases.corner(left, brake, Advice.Gear(g))
            assertTrue(CueClips.covers(p, PolishPhrases), p)
        }
    }

    @Test
    fun `a pass in 3rd where the plan has 2nd is asked for 2nd - after the entry`() {
        val target = pass(inSecond, time = 6_000_000, brakeAt = 110.0, gear = 2)
        assertEquals(2, gearChange(pass(inThird), 2, gearbox))
        // Already in 2nd, or no engine speed, or no gearbox: nothing.
        assertNull(gearChange(pass(inSecond), 2, gearbox))
        assertNull(gearChange(pass(null), 2, gearbox))
        assertNull(gearChange(pass(inThird), 2, null))
        // A pass so fast that 2nd would be near the top of the engine (110 km/h: 7 900 rpm): nothing.
        assertNull(gearChange(pass(110 / 3.6 * 177.0).copy(minKmh = 110.0), 2, gearbox))
        val asked = cornerAdvice(pass(inThird), target, left, 2, gearbox)
        assertEquals(Advice.BrakeLater(10, at = metresBeforeCorner(110.0)), asked[0])
        assertEquals(Advice.Gear(2), asked[1])
        // Not tied to a time lost: the car pulls harder in 2nd however fast the pass was.
        val quick = pass(inThird, time = 5_900_000)
        assertEquals(listOf<Advice>(Advice.Gear(2)), cornerAdvice(quick, target, left, 2, gearbox))
    }

    @Test
    fun `the cue worked when the next pass is in the gear asked for`() {
        val before = pass(inThird)
        assertTrue(improved(Advice.Gear(2), before, pass(inSecond), gearbox))
        assertFalse(improved(Advice.Gear(2), before, pass(inThird), gearbox))
        assertFalse(improved(Advice.Gear(2), before, pass(null), gearbox))
    }

    @Test
    fun `the log keeps the gear`() {
        val brake = Advice.BrakeLater(10, at = 80)
        val cue = Cue(
            3, 1_000, 5, CueKind.Corner, Phrases.corner(left, brake, Advice.Gear(2)), 2, brake, pass(inThird),
            pass(inSecond, time = 6_000_000), turn = 7, also = Advice.Gear(2),
        )
        val text = CoachEvents.cueText(cue)
        assertTrue("gear:2" in text, text)
        assertEquals(Advice.Gear(2), CoachEvents.parseCue(cue.tMicros, text)?.also)
        val vague = cue.copy(advice = Advice.Gear(3, precise = false), also = null)
        assertEquals(vague.advice, CoachEvents.parseCue(0, CoachEvents.cueText(vague))?.advice)
    }

    private fun metresBeforeCorner(brakeAt: Double) = ((left.apexAt - brakeAt) / 10).toInt() * 10
}
