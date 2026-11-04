package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.analysis.review.SessionListTrackDay
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/** Which tracks of a day get a Track day card in its heading (FBBD-315, UI-210 B, FBBD-453). */
class TrackDaysTest {
    private fun s(id: String, start: Long, track: String, state: SessionState = SessionState.FINISHED) =
        SessionSummary(id, File(id), SessionInfo(start, track = track), state, 600_000_000L)

    private fun trackDays(list: List<SessionSummary>, facts: Map<String, SessionFacts> = emptyMap()) =
        sessionDays(list, facts, ZoneOffset.UTC).single().trackDays

    @Test
    fun `a track driven twice that day links its sessions oldest first`() {
        val day = listOf(s("c", 3, "Jastrząb"), s("b", 2, "Silesia Ring"), s("a", 1, "Jastrząb"))
        assertEquals(listOf(SessionListTrackDay("Jastrząb", listOf("a", "c"))), trackDays(day))
    }

    @Test
    fun `a recording session or one without a track takes no part`() {
        val day = listOf(s("c", 3, "Jastrząb", SessionState.RECORDING), s("b", 2, ""), s("a", 1, "Jastrząb"))
        assertEquals(emptyList<SessionListTrackDay>(), trackDays(day))
    }

    @Test
    fun `sessions timed at one saved track are one day whatever name they were recorded with`() {
        val day = listOf(s("c", 3, "Jastrząb"), s("b", 2, "Autodrom Jastrząb"), s("a", 1, "Autodrom Jastrząb"))
        val facts = day.associate { it.id to SessionFacts(trackId = "j", trackName = "Jastrząb") }
        assertEquals(listOf(SessionListTrackDay("Jastrząb", listOf("a", "b", "c"))), trackDays(day, facts))
    }
}
