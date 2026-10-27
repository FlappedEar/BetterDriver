package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanBrakeRoom
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.ProofAsk
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.analysis.line.lineProof
import com.flappedear.betterdriver.analysis.line.planBefore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineProofTest {
    private fun pass(seconds: Double) = PlanPass("s", "", 1, 100.0, seconds)

    private fun corner(id: String, gain: Double, best: Double?, target: Double = 9.7) = PlanCorner(
        cornerId = id, name = "Corner $id", turnsLeft = true, startMeters = 0.0, endMeters = 1.0,
        gainSeconds = gain, reference = pass(10.0), target = pass(target),
        advice = if (gain > 0) PlanAdvice(apexMeters = 5.0) else PlanAdvice(), sessionBestSeconds = best,
    )

    private fun plan(session: String, start: Long, revision: Int?, vararg corners: PlanCorner) = LinePlan(
        trackId = "t", sessionId = session, sessionStartUtcMillis = start, identityRevision = revision,
        referenceLapNumber = 1, referenceLapSeconds = 100.0, laps = 10, sessions = 2, corners = corners.toList(),
    )

    @Test
    fun `free focus places go to the braking points beyond his best - most time first`() {
        fun room(seconds: Double) = PlanBrakeRoom(10, seconds, 18, 0.37, 0.41, 0.85, listOf("Corner 4", "Corner 7"))
        val c5 = corner("c5", 0.0, 9.7).copy(brakeRoom = room(0.20))
        val c9 = corner("c9", 0.0, 9.7).copy(brakeRoom = room(0.21))
        // Driven targets first (UI-277 A), however much a braking point is worth.
        val before = plan("a", 1, 3, corner("c1", 0.11, 9.95), corner("c2", 0.0, 9.7), c5, c9)
        assertEquals(listOf("c1", "c9", "c5"), before.focus().map { it.cornerId })
        assertEquals(listOf("c1"), before.focus(1).map { it.cornerId })
        val full = plan("a", 1, 3, corner("c1", 0.3, 10.0), corner("c2", 0.2, 10.0), corner("c3", 0.1, 10.0), c9)
        assertEquals(listOf("c1", "c2", "c3"), full.focus().map { it.cornerId })
        // The next session proves a braking point as any focus corner: its fastest pass before and after.
        val after = plan("b", 2, 3, corner("c1", 0.0, 9.8), corner("c9", 0.0, 9.5), corner("c5", 0.0, 9.72))
        val proof = lineProof(before, after)!!
        assertEquals(
            listOf(ProofVerdict.Faster, ProofVerdict.Faster, ProofVerdict.Same),
            proof.corners.map { it.verdict },
        )
    }

    @Test
    fun `each focus corner says how much came back - how much is left and whether the coach spoke there`() {
        val before = plan(
            "a", 1, 3,
            corner("c1", 0.30, 9.95), corner("c2", 0.10, 10.0), corner("c3", 0.06, 10.0), corner("c4", 0.0, 9.7),
        )
        val after = plan("b", 2, 3, corner("c1", 0.0, 9.75), corner("c2", 0.0, 10.02), corner("c4", 0.0, 9.6))
        val proof = lineProof(before, after, cues = mapOf("c1" to 4))!!
        assertEquals(listOf("c1", "c2", "c3"), proof.corners.map { it.cornerId })
        val (c1, c2, c3) = proof.corners
        assertEquals(ProofVerdict.Faster, c1.verdict)
        assertEquals(0.20, c1.wonBackSeconds!!, 1e-9)
        assertEquals(0.05, c1.leftSeconds!!, 1e-9)
        assertEquals(true, c1.coached)
        assertEquals(ProofVerdict.Same, c2.verdict)
        assertEquals(false, c2.coached)
        assertEquals(ProofVerdict.NotDriven, c3.verdict)
        assertEquals(0.20 - 0.02, proof.wonBackSeconds, 1e-9)
    }

    @Test
    fun `corners revised between the sessions are not compared - and the plan before is the latest earlier one`() {
        val before = plan("a", 1, 3, corner("c1", 0.3, 9.95))
        val proof = lineProof(before, plan("b", 2, 4, corner("c1", 0.0, 9.5)))!!
        assertEquals(ProofVerdict.NotComparable, proof.corners.single().verdict)
        assertNull(proof.corners.single().wonBackSeconds)
        assertNull(lineProof(plan("a", 1, 3, corner("c1", 0.0, 9.7)), before), "no focus, no proof")
        val plans = listOf(plan("x", 0, 3), before, plan("c", 5, 3))
        assertEquals("a", planBefore(plans, plan("b", 2, 3))!!.sessionId)
        assertNull(planBefore(plans, plan("first", -1, 3)))
    }

    @Test
    fun `the corners the summary asked for are measured - in its words - whatever the plan's own focus`() {
        val before = plan("a", 1, 3, corner("c1", 0.30, 9.95), corner("c7", 0.0, 8.40))
        val after = plan("b", 2, 3, corner("c1", 0.0, 9.90), corner("c7", 0.0, 8.30))
        val asked = listOf(ProofAsk("c7", "T7", cues = 0), ProofAsk("straight-2", "Back straight", cues = 2))
        val proof = lineProof(before, after, asked)!!
        val t7 = proof.corners.single()
        assertEquals("T7", t7.name)
        assertEquals(ProofVerdict.Faster, t7.verdict)
        assertEquals(false, t7.coached)
        assertEquals(0.10, proof.wonBackSeconds, 1e-9)
        assertNull(lineProof(before, after, listOf(ProofAsk("nowhere", "X"))), "no window, no proof")
    }
}
