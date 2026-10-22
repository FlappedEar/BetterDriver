@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach aims where the line plan does (FBBD-307), until the session beats that twice. */
class PlanAimTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))

    private fun laps(vararg paces: SimCircuit.Pace) = tracedLaps(track, sim.drive(paces.toList()).fixes.asSequence())

    private fun reference(vararg paces: SimCircuit.Pace): CoachReference =
        assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps(*paces)))))

    private fun corner2Cues(reference: CoachReference, vararg paces: SimCircuit.Pace): List<Cue> {
        val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference)
        return sim.drive(paces.toList()).fixes.flatMap { engine.update(it).cues }
            .filter { it.kind == CueKind.Corner && it.corner == 2 }
    }

    /** Corner 2's pass of a lap braked early there: what a plan made on other corner windows may aim at. */
    private val earlyPass by lazy {
        assertNotNull(reference(early2, early2, early2).targets.firstOrNull { it.corner == 2 })
    }

    @Test
    fun `without a plan the aim is the second fastest pass, and a plan of other corners aims nowhere`() {
        val laps = laps(steady, early2, steady, steady)
        val plain = assertNotNull(buildReference(track, listOf(SessionLaps("s1", laps))))
        assertTrue(plain.aims.isEmpty())
        assertNull(plain.planSession)
        // Without a plan the aim is the second fastest pass (FBBD-177).
        assertTrue(plain.aim(2) === plain.targets.filter { it.corner == 2 }[1])
        // A plan whose corner ids the reference does not have aims nowhere.
        val lap2 = laps.first { it.lap.number == 2 }
        val other = PlannedTargets("s1", listOf(PlannedTarget("c2", "s1", lap2.lap.timeMicros / 1e6)))
        val unmatched = assertNotNull(buildReference(track, listOf(SessionLaps("s1", laps)), other))
        assertEquals("s1", unmatched.planSession)
        assertTrue(unmatched.aims.isEmpty())
    }

    @Test
    fun `cues aim at the plan's pass, not the second fastest`() {
        // Earlier, corner 2 was braked later twice: without a plan, early braking there is told to brake later.
        val reference = reference(early2, steady, steady)
        val told = corner2Cues(reference, early2, early2, early2, early2, early2)
        assertTrue(told.any { it.advice is Advice.BrakeLater })
        // The plan aims at an early-braked pass there: the same driving has nothing to be told.
        val planned = reference.copy(aims = listOf(earlyPass))
        assertTrue(corner2Cues(planned, early2, early2, early2, early2, early2).isEmpty())
    }

    @Test
    fun `two passes of the session that beat the plan's aim become the aim`() {
        val planned = reference(early2, early2, early2).copy(aims = listOf(earlyPass))
        // Braking later twice beats the plan's pass, so braking early again is told to brake later.
        val cues = corner2Cues(planned, early2, steady, steady, early2, early2, early2)
        assertTrue(cues.any { it.advice is Advice.BrakeLater }, "$cues")
        // Beaten once only, the plan's pass stays the aim.
        assertTrue(corner2Cues(planned, early2, steady, early2, early2, early2, early2).isEmpty())
    }
}
