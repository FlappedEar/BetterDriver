package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.line.CornerAdvice
import com.flappedear.betterdriver.analysis.line.DayAnalysis
import com.flappedear.betterdriver.analysis.line.DayCorner
import com.flappedear.betterdriver.analysis.line.DayCornerProof
import com.flappedear.betterdriver.analysis.line.DayCornerSession
import com.flappedear.betterdriver.analysis.line.DayProof
import com.flappedear.betterdriver.analysis.line.DaySession
import com.flappedear.betterdriver.analysis.line.LinePass
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track day page (FBBD-315, UI round 48) made from a day's analysis. */
class TrackDayUiTest {
    private fun pass(seconds: Double) = LinePass("", 4, 110.0, seconds, null, null, null, null, null, emptyList())

    private fun corner(
        name: String,
        perLap: Double,
        bestLap: Double,
        advice: CornerAdvice = CornerAdvice(brakeMeters = 6.0),
        trend: List<Double?> = listOf(12.0, 11.0),
    ) = DayCorner(
        segmentId = name, name = name, turnsLeft = false, startMeters = 0.0, endMeters = 1.0,
        target = pass(10.0), bestLap = pass(10.0 + bestLap), typical = pass(10.0 + perLap),
        typicalSeconds = 10.0 + perLap, passes = 8, bestLapGainSeconds = bestLap, perLapGainSeconds = perLap,
        bestLapAdvice = CornerAdvice(), typicalAdvice = advice,
        sessions = trend.mapIndexed { i, t -> DayCornerSession("s$i", "s$i", t, t) },
    )

    private fun day(vararg corners: DayCorner, typical: List<Double?> = listOf(140.0, 112.0, 114.8)) = DayAnalysis(
        sessions = typical.mapIndexed { i, t ->
            DaySession(listOf("10:00", "16:06", "17:20")[i], "s$i", 5, 4, 2, t, t)
        },
        referenceSessionId = "s1", referenceSessionLabel = "16:06", referenceLapNumber = 4,
        referenceLapSeconds = 109.91, typicalLapSeconds = 114.34, corners = corners.toList(), identityRevision = 2,
    )

    @Test
    fun `the page ranks corners by what a typical lap loses and says which kind`() {
        val ui = trackDayUi(
            day(corner("Corner 1", 0.53, 0.0), corner("Corner 3", 0.59, 0.12), corner("Corner 5", 0.0, 0.0)),
            "Jastrząb", "Fri 29 Aug",
        )
        assertEquals("Jastrząb · Fri 29 Aug", ui.title)
        assertEquals("3 sessions · 15 laps · 6 at the day's pace", ui.subtitle)
        assertEquals(listOf("T3", "T1"), ui.corners.map { it.tag })
        assertEquals("Even your best lap loses 0.12 s", ui.corners[0].kind)
        assertTrue(ui.corners[0].learn)
        assertEquals(RepeatText, ui.corners[1].kind)
        assertEquals(listOf("Brake 6 m later"), ui.corners[0].todo)
        assertEquals(listOf(12.0, 11.0), ui.corners[0].trend)
        assertEquals("A typical lap at your pace loses 1.12 s to your targets; your best lap leaves 0.12 s.", ui.loss)
        assertEquals(listOf("T3", "T1"), ui.next.map { it.tag })
        assertTrue(ui.coachLine!!.startsWith("The coach starts your next day here on T3, T1"))
        assertNull(ui.proof)
    }

    @Test
    fun `the chart says how far the typical lap came down and how far it went back up`() {
        assertEquals(
            "Your typical lap got 28 s faster from 10:00 to 16:06; at 17:20 it was 2.80 s slower than at 16:06.",
            overDay(day(corner("Corner 1", 0.5, 0.0))),
        )
        assertEquals(
            "Your typical lap did not get faster after 10:00.",
            overDay(day(corner("Corner 1", 0.5, 0.0), typical = listOf(110.0, 110.02, 110.01))),
        )
        assertNull(overDay(day(corner("Corner 1", 0.5, 0.0), typical = listOf(110.0, null, null))))
    }

    @Test
    fun `a corner with time but nothing measurably different says so, and is not next time's focus`() {
        val ui = trackDayUi(day(corner("Corner 2", 0.3, 0.0, advice = CornerAdvice())), "Jastrząb", "Fri 29 Aug")
        assertEquals(listOf(NoAdviceText), ui.corners.single().todo)
        assertTrue(ui.next.isEmpty())
        assertNull(ui.coachLine)
    }

    @Test
    fun `a corner whose only difference is the wheel says it (FBBD-321)`() {
        val advice = CornerAdvice(steering = listOf(SteerAdvice.OpenEarlier(10)))
        val ui = trackDayUi(day(corner("Corner 2", 0.3, 0.0, advice = advice)), "Jastrząb", "Fri 29 Aug")
        assertEquals(listOf("Straighten 10 m earlier"), ui.corners.single().todo)
    }

    @Test
    fun `the last day's focus is measured corner by corner`() {
        val proof = DayProof(
            "2026-08-28",
            listOf(
                DayCornerProof("T2", "Corner 2", 7.43, 6.22, 6.0, ProofVerdict.Faster),
                DayCornerProof("T3", "Corner 3", 11.52, 11.6, 9.5, ProofVerdict.Same),
                DayCornerProof("T7", "Corner 7", 8.0, null, 7.5, ProofVerdict.NotDriven),
            ),
        )
        val ui = trackDayUi(day(corner("Corner 1", 0.5, 0.0)), "Jastrząb", "Fri 29 Aug", proof, "Thu 28 Aug")
        val p = ui.proof!!
        assertEquals("Last day here (Thu 28 Aug) · what came of it", p.title)
        assertEquals("1.13 s", p.wonBack)
        assertTrue(p.better)
        assertEquals(listOf("Faster", "Same", "Not driven"), p.rows.map { it.verdict })
        assertEquals("1.21 s back a lap · typical 7.43 → 6.22 s", p.rows[0].line)
        assertEquals(listOf(ProofTone.Better, ProofTone.Same, ProofTone.Unknown), p.rows.map { it.tone })
    }
}
