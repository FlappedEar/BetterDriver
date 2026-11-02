package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.SessionListEntry
import com.flappedear.betterdriver.analysis.review.sessionDayLabel
import com.flappedear.betterdriver.analysis.review.sessionDeleteText
import com.flappedear.betterdriver.analysis.review.sessionListDays
import com.flappedear.betterdriver.analysis.review.sessionRowTitle
import com.flappedear.betterdriver.analysis.review.sessionSizeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsListTest {
    private val today = 20_000L
    private val date = { day: Long -> "day$day" }

    @Test
    fun daysAreTodayYesterdayOrTheirDate() {
        assertEquals("Today", sessionDayLabel(today, today, date))
        assertEquals("Yesterday", sessionDayLabel(today - 1, today, date))
        assertEquals("day19990", sessionDayLabel(today - 10, today, date))
    }

    @Test
    fun aDayWithOneTrackNamesItInItsHeadingAndNotOnItsCards() {
        val days = sessionListDays(
            listOf(
                SessionListEntry("c", today, "Silesia Ring"),
                SessionListEntry("b", today, "Silesia Ring"),
                SessionListEntry("a", today - 3, "Tor Poznań"),
                SessionListEntry("z", today - 3, "Autodrom Jastrząb"),
                SessionListEntry("y", today - 3, ""),
            ),
            today,
            date,
        )
        assertEquals(listOf("Today · Silesia Ring", "day19997"), days.map { it.title })
        assertEquals(listOf(listOf("c", "b"), listOf("a", "z", "y")), days.map { it.ids })
        assertEquals(listOf(false, true), days.map { it.trackOnRows })
        assertEquals(listOf("Tor Poznań", "Autodrom Jastrząb"), days[1].tracks)
    }

    @Test
    fun aRowIsItsTimeAndTrack() {
        assertEquals("16:23 · Silesia Ring", sessionRowTitle("16:23", "Silesia Ring", withTrack = true))
        assertEquals("16:23", sessionRowTitle("16:23", "Silesia Ring", withTrack = false))
        assertEquals("16:23", sessionRowTitle("16:23", "", withTrack = true))
    }

    @Test
    fun sizesReadInTheLargestFittingUnit() {
        assertEquals("1 KB", sessionSizeText(10))
        assertEquals("820 KB", sessionSizeText(820_400))
        assertEquals("350 MB", sessionSizeText(350_900_000))
        assertEquals("2.4 GB", sessionSizeText(2_400_000_000))
    }

    @Test
    fun theDeleteQuestionNamesTheSessionTheVideoTheSpaceAndTheDevice() {
        assertEquals(
            "Sat 11 Oct · Tor Poznań: its data and video (2.4 GB) will be removed from this phone. " +
                "Files still on a GoPro stay there. This can't be undone.",
            sessionDeleteText("Sat 11 Oct · Tor Poznań", 2_400_000_000, video = true, device = "phone"),
        )
        assertEquals(
            "Sat 11 Oct: its data will be removed from this iPad. " +
                "Files still on a GoPro stay there. This can't be undone.",
            sessionDeleteText("Sat 11 Oct", null, video = false, device = "iPad"),
        )
    }
}
