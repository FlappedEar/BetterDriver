package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Drive's Pinned cards (UI-308 A): the app hands the shared core (coach DrivePinnedTest, FBBD-421)
 * its sessions with the day each started on in the phone's time zone.
 */
class DrivePinnedTest {
    private val day1 = LocalDate.of(2026, 8, 28).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day2 = LocalDate.of(2026, 8, 29).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val hour = 3_600_000L

    private fun s(id: String, start: Long, track: String, state: SessionState = SessionState.FINISHED) =
        SessionSummary(id, File(id), SessionInfo(start, track = track), state, 600_000_000L)

    @Test
    fun `the last day here is the newest day with a timed session at the track`() {
        val sessions = listOf(
            s("rec", day2 + 5 * hour, "Jastrząb", SessionState.RECORDING),
            s("other", day2 + 4 * hour, "Silesia Ring"),
            s("noLaps", day2 + 3 * hour, "Jastrząb"),
            s("b", day2 + 2 * hour, "Jastrząb"),
            s("a", day2 + 1 * hour, "Jastrząb"),
            s("old", day1 + 1 * hour, "Jastrząb"),
        )
        val facts = mapOf(
            "rec" to SessionFacts(laps = 3, bestMillis = 100_000),
            "other" to SessionFacts(laps = 3, bestMillis = 90_000),
            "noLaps" to SessionFacts(laps = 0),
            "b" to SessionFacts(laps = 5, bestMillis = 109_910),
            "a" to SessionFacts(laps = 4, bestMillis = 113_270),
            "old" to SessionFacts(laps = 4, bestMillis = 108_000),
        )
        val last = lastTimeHere(sessions, facts, "Jastrząb", ZoneOffset.UTC)!!
        assertEquals(LocalDate.of(2026, 8, 29), LocalDate.ofEpochDay(last.epochDay))
        assertEquals(109_910L, last.bestMillis)
        assertEquals(listOf(113_270L, 109_910L), last.sessionBests)
        assertNull(lastTimeHere(sessions, facts, null, ZoneOffset.UTC))
        assertNull(lastTimeHere(sessions, facts, "Tor Poznań", ZoneOffset.UTC))
    }

    @Test
    fun `a session late in the evening counts on its own day where the phone is`() {
        // 23:30 UTC on 28 August is already 29 August in Poland.
        val late = s("late", day2 - hour / 2, "Jastrząb")
        val warsaw = ZoneId.of("Europe/Warsaw")
        val inPoland = pinnedSessions(listOf(late), warsaw).single().epochDay
        val inUtc = pinnedSessions(listOf(late), ZoneOffset.UTC).single().epochDay
        assertEquals(LocalDate.of(2026, 8, 29).toEpochDay(), inPoland)
        assertEquals(LocalDate.of(2026, 8, 28).toEpochDay(), inUtc)
    }
}
