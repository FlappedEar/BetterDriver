package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.optimal.JointThresholds
import com.flappedear.betterdriver.analysis.optimal.RealisticOptimalLap
import com.flappedear.betterdriver.analysis.optimal.RealisticSector
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.RankedTimeLoss
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestRow
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TimeLossRanking
import com.flappedear.betterdriver.analysis.segments.TimeLossRole
import com.flappedear.betterdriver.analysis.segments.TimeLossWindow
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

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

    /**
     * Each lap's times through [ids], in lap order: laps 1 and 3 a little faster than the fastest
     * lap (2) through each, so what the best of each shows is seen on two laps (FBBD-177).
     */
    private fun lapTimes(vararg ids: String, fastest: Double = 1.2, others: Double = 1.0) =
        listOf(others, fastest, others, others + 0.5).mapIndexed { i, seconds ->
            val sectors = ids.map {
                SectorTime(it, "Segment $it", TrackSegmentType.Corner, 0.0, 1.0, seconds = seconds)
            }
            LapSectorTimes(i + 1, sectors = sectors)
        }

    @Test
    fun `the realistic optimal is shown apart from the raw theoretical best, never without it (FBBD-178)`() {
        val realistic = RealisticOptimalLap(
            listOf(RealisticSector("a", "Corner 1", 1, 40.0), RealisticSector("b", "Corner 2", 3, 40.5)),
            joints = emptyList(),
            totalSeconds = 80.5,
            thresholds = JointThresholds(),
        )
        val withBoth = analysis.copy(
            theoreticalBest = TheoreticalBestLap(totalSeconds = 80.0, valid = true),
            realisticOptimal = realistic,
        )
        val ui = analysisUi("t", withBoth, null)!!
        assertEquals(80_000L, ui.theoreticalMillis)
        assertEquals(80_500L, ui.realisticMillis)
        assertEquals(0.7, ui.realisticGainSeconds!!, 1e-9)
        assertEquals(listOf(1, 3), ui.realisticLaps)
        val noRaw = analysisUi("t", analysis.copy(realisticOptimal = realistic), null)!!
        assertNull(noRaw.realisticMillis)
    }

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
                segments = ApprovedSegments(
                    listOf("1", "2", "3").map { TrackSegment(it, "Corner $it", TrackSegmentType.Corner, 0.0, 1.0) },
                    "t",
                ),
                summary = TheoreticalBestSummary(rows, rows),
                losses = TimeLossRanking(losses = listOf(loss(3, "1", 0.4), loss(1, "2", 0.03)), valid = true),
                lapTimes = lapTimes("1"),
            ),
            null,
        )!!
        assertEquals(listOf(GainRow("Corner 1", 0.2, 3, "1")), ui.gains)
        assertEquals(listOf(LossRow(2, 3, "Corner 1", 0.4, "1")), ui.losses)
        // Next session: the best lap's gap in Corner 1, compared on the lap that set the best time there.
        val focus = ui.focus.single()
        assertEquals(true, focus.primary)
        assertEquals(0.2, focus.timeSeconds!!, 1e-9)
        assertEquals("Best lap gap", focus.kind)
        assertEquals("Corner 1", focus.name)
        assertEquals(true, focus.observation.contains("0.200 s slower through Corner 1 than lap 3"))
        assertEquals(2, focus.compareIndex)
        assertEquals(3, focus.compareNumber)
        assertEquals(Confidence.HIGH, focus.confidence.level)
        assertEquals(ConfidenceUi.High, ui.gainConfidence)
        assertEquals("+0.20", ui.gains.single().lossText { String.format(Locale.ROOT, "+%.2f", it) })
    }

    @Test
    fun `a time seen on one lap only is no focus area, and its gain is shown without a number`() {
        val row = TheoreticalBestRow(
            "1", "Corner 1", TrackSegmentType.Corner, 0.0, 1.0, 1.0, 3, actualSeconds = 1.0, lossSeconds = 0.2,
        )
        // Only lap 3 was faster through Corner 1 than the fastest lap.
        val times = lapTimes("1", fastest = 1.2, others = 1.25).mapIndexed { i, lap ->
            if (i != 2) lap else lap.copy(sectors = lap.sectors.map { it.copy(seconds = 1.0) })
        }
        val ui = analysisUi(
            "t",
            analysis.copy(summary = TheoreticalBestSummary(listOf(row), listOf(row)), lapTimes = times),
            null,
        )!!
        assertEquals(emptyList<FocusRow>(), ui.focus)
        val withheld = ui.withheld.single()
        assertEquals("Corner 1", withheld.name)
        assertEquals(true, withheld.why.contains("one lap"))
        val gain = ui.gains.single()
        assertEquals(Confidence.MEDIUM, gain.confidence.level)
        assertEquals(SomeTimeText, gain.lossText { "+%.2f".format(it) })
        assertEquals(true, gain.confidence.note.startsWith("Medium confidence · "))
        // The time to gain it adds to is no more certain than it: said in a word, with why.
        assertEquals(Confidence.MEDIUM, ui.gainConfidence.level)
        assertEquals(true, ui.gainConfidence.why.startsWith("Corner 1: "))
        assertEquals(SomeTimeText, gainText(ui.copy(gainSeconds = 0.2)) { "%.2f".format(it) })
        val sure = ui.copy(gainSeconds = 0.2, gainConfidence = ConfidenceUi.High)
        assertEquals("≈-0.20", gainText(sure) { String.format(Locale.ROOT, "%.2f", it) })
    }

    @Test
    fun `below High a time is said in words, and at Low not at all`() {
        val format = { s: Double -> String.format(Locale.ROOT, "%.2f", s) }
        assertEquals("0.31", timeText(0.31, ConfidenceUi.High, format))
        assertEquals(SomeTimeText, timeText(0.31, ConfidenceUi(Confidence.MEDIUM, "only 2 usable laps"), format))
        assertEquals(UnclearText, timeText(0.31, ConfidenceUi(Confidence.LOW, "lap 3: no position"), format))
        assertEquals("High confidence", ConfidenceUi.High.note)
        assertEquals("Low confidence · lap 3: no position", ConfidenceUi(Confidence.LOW, "lap 3: no position").note)
        assertEquals(Missing, valueText("64 m", ConfidenceUi(Confidence.LOW, "x")))
        assertEquals("64 m", valueText("64 m", ConfidenceUi(Confidence.MEDIUM, "x")))
        assertEquals("64 m", valueText("64 m", null))
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
                lapTimes = lapTimes("c1", "s1", "c2"),
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
