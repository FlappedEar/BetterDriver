package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.dayLabel
import com.flappedear.betterdriver.analysis.progression.dayZone
import com.flappedear.betterdriver.analysis.progression.monthLabel
import com.flappedear.betterdriver.analysis.progression.sessionTimeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The common progression days (FBBD-181) against java.time, which Android's progression used before
 * they moved to common Kotlin: the same days, keys and words over years and across summer time.
 */
class ProgressionDayJvmTest {
    @Test
    fun `days, months and labels are java time's`() {
        val day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        var d = LocalDate.of(1999, 12, 1)
        while (d.isBefore(LocalDate.of(2031, 3, 1))) {
            val p = ProgressionDay(d.toEpochDay())
            assertEquals(d.toString(), p.toString())
            assertEquals(YearMonth.from(d).toString(), p.monthKey())
            assertEquals(day.format(d), p.dayLabel())
            assertEquals(month.format(d), p.monthLabel())
            assertEquals(p, ProgressionDay.parse(d.toString()))
            d = d.plusDays(1)
        }
    }

    @Test
    fun `a session's day and time are the zone's, summer time included`() {
        val session = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
        for (name in listOf("Europe/Warsaw", "America/New_York", "Asia/Kolkata", "UTC")) {
            val zone = ZoneId.of(name)
            var t = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
            val end = Instant.parse("2026-11-30T00:00:00Z").toEpochMilli()
            while (t < end) {
                val local = Instant.ofEpochMilli(t).atZone(zone)
                assertEquals(local.toLocalDate().toEpochDay(), ProgressionDay.at(t, zone.dayZone()).epochDay)
                assertEquals(session.format(local), sessionTimeLabel(t, zone.dayZone()))
                t += 37 * 60_000L
            }
        }
    }
}
