package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.analysis.review.SessionListEntry
import com.flappedear.betterdriver.analysis.review.SessionListTrackDay
import com.flappedear.betterdriver.analysis.review.sessionDayLabel
import com.flappedear.betterdriver.analysis.review.sessionDeleteText
import com.flappedear.betterdriver.analysis.review.sessionListDays
import com.flappedear.betterdriver.analysis.review.sessionListEntry
import com.flappedear.betterdriver.analysis.review.sessionRowTitle
import com.flappedear.betterdriver.analysis.review.sessionSizeText
import com.flappedear.betterdriver.analysis.review.sessionTrackDays
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

    /** Arek's 29 Aug at Jastrząb (FBBD-453): five sessions recorded as the circuit, one as the saved track. */
    private val jastrzab = listOf(
        "s1720" to "Autodrom Jastrząb",
        "s1606" to "Jastrząb",
        "s1437" to "Autodrom Jastrząb",
        "s1303" to "Autodrom Jastrząb",
        "s1129" to "Autodrom Jastrząb",
        "s1000" to "Autodrom Jastrząb",
    )

    private fun entries(facts: (String) -> SessionFacts?) = jastrzab.mapIndexed { i, (id, recorded) ->
        sessionListEntry(id, today, recorded, facts(id), startUtcMillis = (10 - i).toLong(), recording = false)
    }

    @Test
    fun sessionsAtOneSavedTrackAreOneTrackDayWhateverNameTheyWereRecordedWith() {
        val saved = SessionFacts(laps = 4, trackId = "jastrzab", trackName = "Jastrząb")
        val day = sessionListDays(entries { saved }, today, date).single()
        assertEquals(
            listOf(SessionListTrackDay("Jastrząb", listOf("s1000", "s1129", "s1303", "s1437", "s1606", "s1720"))),
            day.trackDays,
        )
        assertEquals("Today · Jastrząb", day.title)
        assertEquals(false, day.trackOnRows)
    }

    @Test
    fun beforeItsFactsAreReadASessionGoesByTheNameItWasRecordedWith() {
        val day = sessionListDays(entries { null }, today, date).single()
        assertEquals(listOf(5), day.trackDays.map { it.ids.size })
        assertEquals(true, day.trackOnRows)
    }

    @Test
    fun sessionsImportedWithoutATrackNameAreATrackDayOnceTheirLapsAreTimedAtASavedTrack() {
        // Arek's 27 Sep at Silesia Ring: three imported sessions with no track name in their metadata
        // (not placed at their first position), each timed at the saved track (FBBD-453).
        val silesia = SessionFacts(laps = 6, trackId = "silesia", trackName = "Silesia Ring")
        val ids = listOf("s1454", "s1353", "s1300")
        val read = ids.mapIndexed { i, id ->
            sessionListEntry(id, today, "", silesia, (3 - i).toLong(), recording = false)
        }
        val day = sessionListDays(read, today, date).single()
        assertEquals(listOf(SessionListTrackDay("Silesia Ring", listOf("s1300", "s1353", "s1454"))), day.trackDays)
        assertEquals("Today · Silesia Ring", day.title)
        val unread = ids.map { sessionListEntry(it, today, "", null, 0, recording = false) }
        assertEquals(emptyList<SessionListTrackDay>(), sessionListDays(unread, today, date).single().trackDays)
    }

    @Test
    fun aRecordingSessionOrOneWithoutATrackTakesNoPart() {
        val day = sessionTrackDays(
            listOf(
                SessionListEntry("c", today, "Jastrząb", trackId = "j", startUtcMillis = 3, recording = true),
                SessionListEntry("b", today, "", startUtcMillis = 2),
                SessionListEntry("a", today, "Jastrząb", trackId = "j", startUtcMillis = 1),
            ),
        )
        assertEquals(emptyList<SessionListTrackDay>(), day)
    }

    @Test
    fun twoTracksOfADayAreTwoTrackDaysMostSessionsFirst() {
        val days = sessionTrackDays(
            listOf(
                SessionListEntry("d", today, "Silesia Ring", startUtcMillis = 4),
                SessionListEntry("c", today, "Jastrząb", startUtcMillis = 3),
                SessionListEntry("b", today, "Silesia Ring", startUtcMillis = 2),
                SessionListEntry("a", today, "Silesia Ring", startUtcMillis = 1),
                SessionListEntry("z", today, "Jastrząb", startUtcMillis = 0),
            ),
        )
        assertEquals(
            listOf(
                SessionListTrackDay("Silesia Ring", listOf("a", "b", "d")),
                SessionListTrackDay("Jastrząb", listOf("z", "c")),
            ),
            days,
        )
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
