package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.review.SessionFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drive's Pinned cards (UI-308 A, FBBD-421), the same on Android and iOS. */
class DrivePinnedTest {
    private val day1 = 20_693L
    private val day2 = 20_694L
    private val hour = 3_600_000L

    private fun s(id: String, day: Long, hours: Int, track: String, recording: Boolean = false) =
        PinnedSession(id, track, day * 86_400_000L + hours * hour, day, recording)

    private fun target(name: String, role: String, seconds: Double?) = FocusTarget(
        id = name, role = role, segmentId = name, name = name, kind = "sectorGap", confidence = "HIGH",
        timeAvailableSeconds = seconds,
    )

    @Test
    fun theFocusListsThePrimaryCornerFirstWithTheTimeOfAllOfThem() {
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
        assertEquals("Corner 3 · Corner 1 · Corner 7", focusCornersText(focus))
        assertEquals("1.53 s a lap to find · Corner 3 first", focusDetailText(focus))
        assertEquals("Corner 3 first", focusDetailText(focus.copy(secondsToFind = null)))
        assertNull(driveFocus(record.copy(targets = emptyList())))
        assertNull(driveFocus(null))
    }

    @Test
    fun theLastDayHereIsTheNewestDayWithATimedSessionAtTheTrack() {
        val sessions = listOf(
            s("rec", day2, 5, "Jastrząb", recording = true),
            s("other", day2, 4, "Silesia Ring"),
            s("noLaps", day2, 3, "Jastrząb"),
            s("b", day2, 2, "Jastrząb"),
            s("a", day2, 1, "Jastrząb"),
            s("old", day1, 1, "Jastrząb"),
        )
        val facts = mapOf(
            "rec" to SessionFacts(laps = 3, bestMillis = 100_000),
            "other" to SessionFacts(laps = 3, bestMillis = 90_000),
            "noLaps" to SessionFacts(laps = 0),
            "b" to SessionFacts(laps = 5, bestMillis = 109_910),
            "a" to SessionFacts(laps = 4, bestMillis = 113_270),
            "old" to SessionFacts(laps = 4, bestMillis = 108_000),
        )
        val last = lastTimeHere(sessions, facts, "Jastrząb")!!
        assertEquals(day2, last.epochDay)
        assertEquals(109_910L, last.bestMillis)
        assertEquals(listOf(113_270L, 109_910L), last.sessionBests)
        assertEquals("best · 2 sessions", lastTimeDetailText(last))
        assertEquals("best · 1 session", lastTimeDetailText(last.copy(sessionBests = listOf(1L))))
        assertNull(lastTimeHere(sessions, facts, null))
        assertNull(lastTimeHere(sessions, facts, "Tor Poznań"))
    }
}
