package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Which tracks of a day get a "Track day ›" link in its heading (FBBD-315, UI-210 B). */
class TrackDaysTest {
    private fun s(id: String, start: Long, track: String, state: SessionState = SessionState.FINISHED) =
        SessionSummary(id, File(id), SessionInfo(start, track = track), state, 600_000_000L)

    @Test
    fun `a track driven twice that day links its sessions oldest first`() {
        val day = listOf(s("c", 3, "Jastrząb"), s("b", 2, "Silesia Ring"), s("a", 1, "Jastrząb"))
        assertEquals(listOf(TrackDayLinkUi("Jastrząb", listOf("a", "c"))), trackDays(day))
    }

    @Test
    fun `a recording session or one without a track takes no part`() {
        val day = listOf(s("c", 3, "Jastrząb", SessionState.RECORDING), s("b", 2, ""), s("a", 1, "Jastrząb"))
        assertEquals(emptyList<TrackDayLinkUi>(), trackDays(day))
    }
}
