package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisUiTest {
    private fun lap(number: Int, seconds: Double, issue: LapReferenceIssue = LapReferenceIssue.None) =
        TimedLap(number, number * 100.0, number * 100.0 + seconds, seconds, referenceIssue = issue)

    private val analysis = SessionAnalysis(
        LapSession(
            LapSessionStatus.entries.first(),
            timedLaps = listOf(lap(1, 82.3), lap(2, 81.2), lap(3, 81.9), lap(4, 95.0, LapReferenceIssue.GpsGap)),
            fastestLapIndex = 1,
        ),
    )

    @Test
    fun `laps are listed with their delta to the fastest and why a lap is not counted`() {
        val ui = analysisUi("t", analysis, null)!!
        assertEquals(listOf(1, 2, 3, 4), ui.laps.map { it.number })
        assertEquals(81_200L, ui.bestLapMillis)
        assertEquals(1.1, ui.laps[0].deltaSeconds!!, 1e-9)
        assertEquals(true, ui.laps[1].best)
        assertNull(ui.laps[3].deltaSeconds)
        assertEquals("GPS gap", ui.laps[3].note)
        assertNull(ui.theoreticalMillis)
        assertEquals(true, ui.sectorsNote!!.startsWith("No corners"))
    }

    @Test
    fun `the second fastest counted lap is compared first`() {
        assertEquals(2, defaultComparedLap(analysis))
    }

    @Test
    fun `no counted lap, no screen model`() {
        assertNull(analysisUi("t", SessionAnalysis(LapSession(LapSessionStatus.entries.first())), null))
    }

    @Test
    fun `chart values are interpolated within a run and missing between runs`() {
        val runs = listOf(listOf(ChartPoint(0.0, 0.0), ChartPoint(10.0, 1.0)), listOf(ChartPoint(20.0, 5.0)))
        assertEquals(0.5, valueAt(runs, 5.0)!!, 1e-12)
        assertNull(valueAt(runs, 15.0))
        assertEquals(5.0, valueAt(runs, 20.0)!!, 1e-12)
    }
}
