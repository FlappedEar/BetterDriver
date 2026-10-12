package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.RankedTimeLoss
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestRow
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TimeLossRanking
import com.flappedear.betterdriver.analysis.segments.TimeLossRole
import com.flappedear.betterdriver.analysis.segments.TimeLossWindow
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
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
    fun `where time goes lists meaningful losses, largest first, with the lap to compare`() {
        fun row(id: String, loss: Double?, source: Int) = TheoreticalBestRow(
            id, "Corner $id", TrackSegmentType.Corner, 0.0, 1.0, 1.0, source, actualSeconds = 1.0, lossSeconds = loss,
            consistency = ConsistencySummary(count = 3, interquartileRange = 0.07, available = true),
        )
        val rows = listOf(row("1", 0.2, 3), row("2", 0.01, 2), row("3", null, 1))
        fun loss(lap: Int, id: String, seconds: Double) = RankedTimeLoss(
            lap, 0.0, TimeLossWindow(id, "Corner $id", TrackSegmentType.Corner, TimeLossRole.Corner, "", 0.0, 1.0),
            seconds, 1.0, 1.0,
        )
        val ui = analysisUi(
            "t",
            analysis.copy(
                summary = TheoreticalBestSummary(rows, rows),
                losses = TimeLossRanking(losses = listOf(loss(3, "1", 0.4), loss(1, "2", 0.03)), valid = true),
            ),
            null,
        )!!
        assertEquals(listOf(GainRow("Corner 1", 0.2, 3)), ui.gains)
        assertEquals(listOf(LossRow(2, 3, "Corner 1", 0.4)), ui.losses)
        // Next session: the best lap's gap in Corner 1, compared on the lap that set the best time there.
        val focus = ui.focus.single()
        assertEquals("Best lap gap", focus.kind)
        assertEquals("Corner 1", focus.name)
        assertEquals(true, focus.observation.contains("0.200 s slower through Corner 1 than lap 3"))
        assertEquals(2, focus.compareIndex)
        assertEquals(3, focus.compareNumber)
    }

    @Test
    fun `corners in where time goes and next session can be picked for the coach, each on one row`() {
        fun row(id: String, type: TrackSegmentType, loss: Double) = TheoreticalBestRow(
            id, "Segment $id", type, 0.0, 1.0, 1.0, 3, actualSeconds = 1.0, lossSeconds = loss,
        )
        val rows = listOf(row("c1", TrackSegmentType.Corner, 0.3), row("s1", TrackSegmentType.Straight, 0.2))
        fun loss(lap: Int, id: String, type: TrackSegmentType, seconds: Double) = RankedTimeLoss(
            lap, 0.0, TimeLossWindow(id, "Segment $id", type, TimeLossRole.Corner, "", 0.0, 1.0), seconds, 1.0, 1.0,
        )
        val segments = ApprovedSegments(
            listOf(
                TrackSegment("c1", "Segment c1", TrackSegmentType.Corner, 0.0, 1.0),
                TrackSegment("s1", "Segment s1", TrackSegmentType.Straight, 1.0, 2.0),
                TrackSegment("c2", "Segment c2", TrackSegmentType.Corner, 2.0, 3.0),
            ),
            "auto-lap-2",
        )
        val losses = listOf(
            loss(3, "c1", TrackSegmentType.Corner, 0.4),
            loss(1, "c2", TrackSegmentType.Corner, 0.3),
            loss(3, "s1", TrackSegmentType.Straight, 0.2),
        )
        val ui = analysisUi(
            "t",
            analysis.copy(
                segments = segments,
                summary = TheoreticalBestSummary(rows, rows),
                losses = TimeLossRanking(losses = losses, valid = true),
            ),
            null,
        )!!
        assertEquals(listOf("c1", null), ui.gains.map { it.cornerId })
        assertEquals(listOf("c1", "c2", null), ui.losses.map { it.cornerId })
        assertEquals("c1", ui.focus.first().cornerId)
        // Nothing to pick until the coach's picks are known.
        assertEquals(false, firstCornerRows(ui).any)
        val rowsWithToggle = firstCornerRows(ui.copy(coachPickable = setOf("c1", "c2")))
        assertEquals(setOf(0), rowsWithToggle.gains)
        assertEquals(setOf(1), rowsWithToggle.losses)
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
