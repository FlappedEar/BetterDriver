package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanMark
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.PlanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where to find time (FBBD-306): what a corner says (UI-166 A) and how the plan becomes the view. */
class LineUiTest {
    private fun pass(session: String, label: String, brake: Boolean = true) = PlanPass(
        sessionId = session,
        sessionLabel = label,
        lapNumber = 3,
        lapSeconds = 111.24,
        seconds = 10.0,
        brake = if (brake) PlanMark(100.0, 50.0, 19.0) else null,
        apex = PlanMark(150.0, 50.0005, 19.0005, 52.0),
        line = (0..10).map { PlanPoint(100.0 + it * 2, 50.0 + it * 1e-5, 19.0, pedal = null) },
    )

    private fun corner(gain: Double, advice: PlanAdvice, name: String = "Corner 4") = PlanCorner(
        cornerId = null, name = name, turnsLeft = true, startMeters = 90.0, endMeters = 130.0,
        gainSeconds = gain, reference = pass("s1", ""), target = pass("s0", "29 Aug 2026, 17:20"),
        advice = advice,
    )

    @Test
    fun `a corner says what to do in words, nothing the receiver cannot repeat`() {
        val todo = cornerTodo(corner(0.11, PlanAdvice(apexMeters = -5.2, tighterMeters = 2.1, pickupMeters = -4.6)))
        assertEquals(
            listOf("Brake at the same place", "Apex 5 m earlier and 2 m tighter", "Back on the throttle 5 m earlier"),
            todo,
        )
        assertEquals(
            listOf("Brake 6 m later", "Apex 3 m wider", "Carry 2 km/h more through the slowest point"),
            cornerTodo(corner(0.2, PlanAdvice(brakeMeters = 5.8, tighterMeters = -3.0, minimumSpeedKmh = 2.4))),
        )
        assertEquals(listOf("Your best lap is as quick as your target here."), cornerTodo(corner(0.0, PlanAdvice())))
        assertEquals(
            listOf("The same as your best lap within GPS accuracy: no advice."),
            cornerTodo(corner(0.08, PlanAdvice())),
        )
    }

    @Test
    fun `the view is the plan of its own session, ranked, with where each target is from`() {
        val plan = LinePlan(
            trackId = "t", sessionId = "s1", sessionStartUtcMillis = 1L, referenceLapNumber = 2,
            referenceLapSeconds = 109.91, laps = 25, sessions = 6,
            corners = listOf(
                corner(0.11, PlanAdvice(apexMeters = -5.0), "Corner 1"),
                corner(0.3, PlanAdvice(apexMeters = 8.0), "Corners 10–11"),
            ),
        )
        assertNull(lineUi(plan, "another session"))
        val ui = lineUi(plan, "s1")!!
        assertEquals(listOf("T10–11", "T1"), ui.ranked.map { it.tag })
        assertEquals(0.41, ui.totalGainSeconds, 1e-9)
        assertEquals("29 Aug 2026, 17:20, lap 3 (1:51.24)", ui.corners[0].targetFrom)
        // Metres from the first point of the lap: the line starts where the map does.
        assertEquals(0.0, ui.corners[0].reference.first().eastMeters, 1e-6)
        assertEquals(11.1, ui.corners[0].reference.last().northMeters, 0.05)
    }

    @Test
    fun `the perfect lap in 3D comes with the view, and says where the ghost is`() {
        // Without the road the laps used there is nothing to drive on.
        assertNull(lineUi(LinePlan(
            trackId = "t", sessionId = "s1", sessionStartUtcMillis = 1L, referenceLapNumber = 2,
            referenceLapSeconds = 109.91, laps = 25, sessions = 6, corners = listOf(corner(0.11, PlanAdvice())),
        ), "s1")!!.scene)
        val plan = previewPerfectLapPlan()
        val scene = lineUi(plan, "s1")!!.scene!!
        assertEquals(112.41 - 0.42, scene.perfectSeconds, 1e-9)
        assertEquals(0.42, scene.gapAt(scene.durationSeconds), 0.01)
        val hud = sceneHud(scene, scene.corners[2].startSeconds + 1.0)
        assertEquals("T3", hud.corner)
        assertEquals("Target from this session, lap 2", hud.from)
        assertEquals("Your best lap +0.42 s", ghostText(0.4199))
        assertEquals("Your best lap −0.05 s", ghostText(-0.05))
    }
}
