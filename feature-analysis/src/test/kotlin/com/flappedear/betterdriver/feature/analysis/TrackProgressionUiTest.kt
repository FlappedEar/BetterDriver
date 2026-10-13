package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.progression.ProgressionFilter
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.coachingProgression
import com.flappedear.betterdriver.analysis.progression.trackProgression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The progression screen's words (FBBD-181): each metric's latest value and trend, a number only
 * where it is precise, imported sessions marked, the sessions left out, the coaching point by point.
 */
class TrackProgressionUiTest {
    private val ui = previewProgressionUi()

    private fun corner(name: String) = ui.corners.single { it.name == name }

    /** A corner opens its passes in the latest session that drove it, in the revision shown (FBBD-93). */
    @Test
    fun aCornerOpensItsPassesInTheLatestSessionThatDroveIt() {
        assertEquals(listOf("p3", "p3", "p3", "p3"), ui.corners.map { it.sessionId })
        val records = PreviewProgressionRecords
        val zone = ZoneOffset.UTC
        // With the latest left out, the one before it.
        val earlier = trackProgression(
            records, ProgressionGrouping.Session, ProgressionFilter(sessionIds = setOf("p0", "p1", "p2")), zone,
        )
        assertEquals("p2", latestSessionWith(records, earlier, "c2"))
        assertNull(latestSessionWith(records, earlier, "c-unknown"))
        // A session of another revision never opens for the corner: its CornerIds are other corners.
        val changed = records.map { if (it.sessionId == "p3") it.copy(identityRevision = 2) else it }
        val shown = trackProgression(changed, ProgressionGrouping.Session, ProgressionFilter(), zone)
        assertEquals("p3", latestSessionWith(changed, shown, "c2"))
        val old = shown.copy(revision = 1)
        assertEquals("p2", latestSessionWith(changed, old, "c2"))
    }

    @Test
    fun lapsShowTheLatestValueAndTheirTrendWithItsSize() {
        assertEquals(
            listOf("Best lap", "Typical lap (median)", "Lap spread (middle half)", "Lap variation (std. dev.)"),
            ui.laps.map { it.label },
        )
        val best = ui.laps[0]
        assertEquals("1:30.75", best.latest)
        assertEquals("1.80 s quicker over 4 sessions", best.trend)
        assertEquals(TrendTone.Better, best.tone)
        assertEquals(4, best.values.size)
        assertTrue(best.invert)
        // Every session's laps are equally spread: steady.
        assertEquals(TrendTone.Steady, ui.laps[2].tone)
    }

    @Test
    fun aCornerSaysItsNumbersOnlyWherePrecise() {
        val c2 = corner("Corner 2")
        assertTrue(c2.time.latest.endsWith(" s"))
        assertEquals("0.48 s quicker over 4 sessions", c2.time.trend)
        val braking = c2.details.single { it.label == "Braking point" }
        assertEquals("9 m later over 4 sessions", braking.trend)
        assertEquals(TrendTone.Neutral, braking.tone)
        val minimum = corner("Corner 1").details.single { it.label == "Minimum speed" }
        assertEquals("81 km/h", minimum.latest)

        // Corner 4 has a Medium pass in every session: its time trends, without a number.
        val c4 = corner("Corner 4")
        assertEquals("", c4.time.latest)
        assertTrue(c4.time.doubtful)
        assertEquals("Quicker over 4 sessions", c4.time.trend)
        // Corner 3's braking points are inferred: never a number.
        val inferred = corner("Corner 3").details.single { it.label == "Braking point" }
        assertEquals("", inferred.latest)
        assertTrue(inferred.doubtful)
        // 3 m later over the sessions: within what a braking point moves anyway.
        assertEquals("Steady over 4 sessions", inferred.trend)
    }

    @Test
    fun importedSessionsAreMarkedWithWhereTheyCameFrom() {
        assertEquals(listOf("p3", "p2", "p1", "p0"), ui.points.map { it.key })
        assertEquals("Imported · VBO lap-12.vbo", ui.points.single { it.key == "p2" }.origin)
        assertNull(ui.points.single { it.key == "p3" }.origin)
        assertEquals("4 sessions on 3 days · 1 imported", ui.summary)
        assertEquals("5 counted laps", ui.points[0].detail)
    }

    @Test
    fun trackDaysPoolTheirSessionsAndOpenToThem() {
        val days = previewProgressionUi(ProgressionGrouping.TrackDay)
        assertEquals(3, days.points.size)
        val first = days.points.last()
        assertEquals("5 Sep 2026", first.label)
        assertEquals("2 sessions · 10 counted laps", first.detail)
        assertEquals(LocalDate.of(2026, 9, 5), first.day)
        assertEquals("1 imported · VBO lap-12.vbo", days.points[1].origin)
        assertEquals("1.20 s quicker over 3 track days", days.laps[0].trend)
    }

    @Test
    fun aSessionLeftOutStaysListedButCountsNoMore() {
        val zone = ZoneOffset.UTC
        val records = PreviewProgressionRecords
        val listed = trackProgression(records, zone = zone)
        val counted = ProgressionFilter(sessionIds = setOf("p0", "p1", "p2"))
        val shown = trackProgression(records, filter = counted, zone = zone)
        val left = progressionUi(
            "Sim Raceway", shown, listed, coachingProgression(shown, records), setOf("p3"),
            ProgressionPeriod.All, null, zone,
        )
        assertTrue(left.points.single { it.key == "p3" }.excluded)
        assertEquals("1:31.35", left.laps[0].latest)
        assertEquals("4 sessions on 3 days · 1 imported · 1 left out", left.summary)
    }

    @Test
    fun theCoachingSaysWhatCameOfEachFocus() {
        // Newest first; p1 worked on p0's focus with the coach, p2 and p3 still had it as theirs.
        assertEquals(3, ui.coaching.size)
        val p1 = ui.coaching.last()
        assertEquals("5 Sep 2026, 15:00", p1.label)
        assertEquals("Corner 2: improved", p1.text)
        assertEquals(TrendTone.Better, p1.tone)
        assertEquals("3 of 3 focuses improved their corner.", ui.coachingSummary)
    }

    @Test
    fun twoSessionsAreTooFewForATrend() {
        val zone = ZoneOffset.UTC
        val two = trackProgression(PreviewProgressionRecords.take(2), zone = zone)
        val u = progressionUi("x", two, two, emptyList(), emptySet(), ProgressionPeriod.All, null, zone)
        assertEquals("Too few sessions for a trend", u.laps[0].trend)
        assertEquals(TrendTone.Unknown, u.laps[0].tone)
        assertEquals(listOf("A trend needs 3 sessions."), u.notes)
    }

    @Test
    fun aPeriodADayAndSessionsLeftOutFilterWhatIsShown() {
        val zone = ZoneOffset.UTC
        val records = PreviewProgressionRecords
        val today = LocalDate.of(2026, 10, 20)
        fun keys(choice: ProgressionChoice) =
            progressionScreenUi("x", records, choice, today, zone).points.map { it.key }
        assertEquals(listOf("p3", "p2", "p1", "p0"), keys(ProgressionChoice()))
        // The last 90 days reach back to 22 July; a 30-day period would not, but there is none.
        assertEquals(listOf("p3", "p2", "p1", "p0"), keys(ProgressionChoice(period = ProgressionPeriod.Quarter)))
        assertEquals(listOf("p1", "p0"), keys(ProgressionChoice(day = LocalDate.of(2026, 9, 5))))
        val oct5 = ProgressionChoice(period = ProgressionPeriod.Quarter, day = LocalDate.of(2026, 10, 5))
        assertEquals(listOf("p3"), keys(oct5))
        val later = progressionScreenUi("x", records, ProgressionChoice(), LocalDate.of(2027, 9, 20), zone)
        assertEquals(4, later.points.size)
        val lastYear = ProgressionChoice(period = ProgressionPeriod.Year)
        val year = progressionScreenUi("x", records, lastYear, LocalDate.of(2027, 9, 20), zone)
        assertEquals(listOf("p3"), year.points.map { it.key })

        val left = progressionScreenUi("x", records, ProgressionChoice(excluded = setOf("p0", "p1")), today, zone)
        assertEquals(4, left.points.size)
        assertEquals(listOf(true, true), left.points.filter { it.key in setOf("p0", "p1") }.map { it.excluded })
        // Only the two sessions still counted: a trend needs a third.
        assertEquals("Too few sessions for a trend", left.laps[0].trend)
    }

    /**
     * A track day opened alone (FBBD-175) shows its sessions' lap and corner times and, under the
     * laps, every counted lap of the day in the order driven: on 5 Sep each session fades by 0.15 s
     * a lap, and the second starts 0.6 s quicker, so over the ten laps the least-squares line falls
     * 0.49 s. No other view has the row.
     */
    @Test
    fun aTrackDayShowsEveryLapOfTheDayInOrder() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 20)
        val day = progressionScreenUi(
            "x", PreviewProgressionRecords, ProgressionChoice(day = LocalDate.of(2026, 9, 5)), today, zone,
        )
        assertEquals(2, day.laps[0].values.size)
        assertEquals(4, day.corners.size)
        val laps = day.dayLaps!!
        assertEquals(DayLapsLabel, laps.label)
        assertEquals(10, laps.values.size)
        assertEquals(92.55, laps.values.first()!!, 1e-9)
        assertEquals("1:32.55", laps.latest)
        assertEquals("0.49 s quicker over 10 laps", laps.trend)
        assertEquals(TrendTone.Better, laps.tone)
        assertTrue(laps.invert)
        assertNull(progressionScreenUi("x", PreviewProgressionRecords, ProgressionChoice(), today, zone).dayLaps)
    }
}
