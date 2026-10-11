package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LiveUiTest {
    private val live = LiveSnapshot(
        sessionId = "s",
        startUtcMillis = 0,
        elapsedMicros = 754_000_000,
        fixes = 100,
        fixRateHz = 25,
        lastFixAgeMicros = 40_000,
        sats = 14,
        speedKmh = 141.6,
        storageLowBytes = null,
        stoppedReason = null,
        droppedSamples = 0,
    )

    @Test
    fun clockAndDuration() {
        assertEquals("0:00", formatClock(0))
        assertEquals("12:34", formatClock(754_000_000))
        assertEquals("1:02:03", formatClock(3_723_000_000))
        assertEquals("<1 min", formatDuration(59_000_000))
        assertEquals("18 min", formatDuration(18 * 60_000_000L + 5))
    }

    @Test
    fun recordingShowsLiveValuesAndNoAlert() {
        val s = liveTrackState(RecorderState(connected = true, live = live))
        assertNull(s.alert)
        assertEquals("12:34", s.recordingTime)
        assertEquals("25 Hz · 14 sats", s.status.single().value)
        assertEquals("142", s.tiles.first().value)
        assertNull(s.deltaSeconds)
    }

    @Test
    fun alertsWhenNotRecordingOrGnssLost() {
        assertEquals(AlertLevel.Warning, liveTrackState(RecorderState(starting = true)).alert?.level)
        val failed = liveTrackState(RecorderState(connected = true, error = "Storage full. The session is saved."))
        assertEquals("Not recording", failed.alert?.title)
        assertEquals("Storage full. The session is saved.", failed.alert?.detail)
        val lost = liveTrackState(RecorderState(connected = true, live = live.copy(lastFixAgeMicros = 3_000_000)))
        assertEquals("GNSS signal lost", lost.alert?.title)
        assertEquals("Lost", lost.status.single().value)
    }

    @Test
    fun sessionLines() {
        val s = SessionSummary(
            "id",
            File("id"),
            SessionInfo(1_760_176_920_000, car = "2023 Toyota Corolla", track = ""),
            SessionState.RECOVERED,
            18 * 60_000_000L,
        )
        assertEquals("2023 Toyota Corolla · 18 min", sessionDetail(s))
        assertNull(lastSessionLine(listOf(s.copy(state = SessionState.RECORDING))))
        assertEquals(true, lastSessionLine(listOf(s))?.endsWith("18 min · recovered"))
        assertEquals("BetterDriver-id.vbo", vboFileName(s))
    }
}
