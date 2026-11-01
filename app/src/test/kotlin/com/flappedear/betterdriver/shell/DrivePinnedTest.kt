package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Drive's Pinned cards (UI-308 A): the next session's focus here, and the last day here. */
class DrivePinnedTest {
    private val day1 = LocalDate.of(2026, 8, 28).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day2 = LocalDate.of(2026, 8, 29).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val hour = 3_600_000L

    private fun s(id: String, start: Long, track: String, state: SessionState = SessionState.FINISHED) =
        SessionSummary(id, File(id), SessionInfo(start, track = track), state, 600_000_000L)

    private fun target(name: String, role: String, seconds: Double?) = FocusTarget(
        id = name, role = role, segmentId = name, name = name, kind = "sectorGap", confidence = "HIGH",
        timeAvailableSeconds = seconds,
    )

    @Test
    fun `the focus lists the primary corner first with the time of all of them`() {
        val record = SessionFocusRecord(
            trackId = "t",
            sessionId = "a",
            sessionStartUtcMillis = 1,
            targets = listOf(
                target("Corner 1", FocusTarget.Secondary, 0.53),
                target("Corner 3", FocusTarget.Primary, 0.59),
                target("Corner 7", FocusTarget.Secondary, 0.41),
            ),
        )
        val focus = driveFocus(record)!!
        assertEquals(listOf("Corner 3", "Corner 1", "Corner 7"), focus.corners)
        assertEquals(1.53, focus.secondsToFind!!, 1e-9)
        assertEquals("1.53 s a lap to find", focusTimeText(focus.secondsToFind!!))
        assertNull(driveFocus(record.copy(targets = emptyList())))
        assertNull(driveFocus(null))
    }

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
        assertEquals(LocalDate.of(2026, 8, 29), last.day)
        assertEquals(109_910L, last.bestMillis)
        assertEquals(listOf(113_270L, 109_910L), last.sessionBests)
        assertNull(lastTimeHere(sessions, facts, null, ZoneOffset.UTC))
        assertNull(lastTimeHere(sessions, facts, "Tor Poznań", ZoneOffset.UTC))
    }
}
