package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.dayLabel
import com.flappedear.betterdriver.analysis.progression.monthLabel
import com.flappedear.betterdriver.analysis.progression.sessionTimeLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The progression's days (FBBD-181) as java.time counts and writes them, on Android and iOS alike. */
class ProgressionDayTest {
    private val hour = 3_600_000L

    @Test
    fun daysCountAndReadAsIsoDates() {
        assertEquals(0L, ProgressionDay.of(1970, 1, 1).epochDay)
        assertEquals(20_701L, ProgressionDay.of(2026, 9, 5).epochDay)
        assertEquals("2026-09-05", ProgressionDay(20_701).toString())
        assertEquals("2026-09", ProgressionDay(20_701).monthKey())
        assertEquals(ProgressionDay.of(2024, 2, 29), ProgressionDay.parse("2024-02-29"))
        assertEquals("2024-03-01", ProgressionDay.parse("2024-02-29").plusDays(1).toString())
        assertEquals("1969-12-31", ProgressionDay(-1).toString())
        assertTrue(ProgressionDay.of(2026, 9, 5).isBefore(ProgressionDay.of(2026, 9, 6)))
        assertFailsWith<IllegalArgumentException> { ProgressionDay.parse("2026-02-30") }
        assertFailsWith<IllegalArgumentException> { ProgressionDay.parse("5 Sep") }
    }

    @Test
    fun aDayIsTheZonesOwn() {
        val late = ProgressionDay.of(2026, 9, 5).epochDay * 24 * hour + 23 * hour + hour / 2
        assertEquals("2026-09-05", ProgressionDay.at(late, DayZone.Utc).toString())
        assertEquals("2026-09-06", ProgressionDay.at(late, DayZone.fixed(2 * hour)).toString())
        assertEquals("2026-09-05", ProgressionDay.at(late, DayZone.fixed(-5 * hour)).toString())
    }

    @Test
    fun daysMonthsAndSessionsAreWrittenInEnglish() {
        val day = ProgressionDay.of(2026, 9, 5)
        assertEquals("5 Sep 2026", day.dayLabel())
        assertEquals("September 2026", day.monthLabel())
        val at = day.epochDay * 24 * hour + 15 * hour + 7 * 60_000L
        assertEquals("5 Sep 2026, 15:07", sessionTimeLabel(at, DayZone.Utc))
        assertEquals("6 Sep 2026, 00:07", sessionTimeLabel(at, DayZone.fixed(9 * hour)))
    }
}
