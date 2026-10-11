package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.timing.live.LiveSector
import com.flappedear.betterdriver.timing.live.LiveTimingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LiveUiTest {
    private val tiles = listOf(TrackChannel.SPEED, TrackChannel.G, TrackChannel.LAP)
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
        val s = liveTrackState(RecorderState(connected = true, live = live), tiles)
        assertNull(s.alert)
        assertEquals("12:34", s.recordingTime)
        assertEquals("25 Hz · 14 sats", s.status.single().value)
        assertEquals("142", s.tiles.first().value)
        assertEquals("–", s.tiles[1].value)
        assertNull(s.deltaSeconds)
    }

    @Test
    fun tilesFollowTheSessionChoice() {
        val withG = RecorderState(connected = true, live = live.copy(gCombined = 1.123))
        val s = liveTrackState(withG, tiles)
        assertEquals(listOf("Speed", "G", "Lap"), s.tiles.map { it.label })
        assertEquals("1.12", s.tiles[1].value)
        val two = liveTrackState(RecorderState(connected = true, live = live), tiles.reversed().minus(TrackChannel.G))
        assertEquals(listOf("Lap", "Speed"), two.tiles.map { it.label })
    }

    @Test
    fun timedSessionShowsTrackLapAndDelta() {
        val timing = LiveTimingState(
            lapNumber = 3, lapMillis = 47_300, deltaSeconds = -0.34, predictedMillis = 112_070,
            lastMillis = 112_900, bestMillis = 112_410, theoreticalBestMillis = 111_800,
            sectors = listOf(
                LiveSector("S1", -0.1, 0.05, current = false),
                LiveSector("S2", null, null, current = true),
            ),
        )
        val timed = RecorderState(connected = true, live = live, place = "Tor Poznań", timing = timing)
        val s = liveTrackState(timed, tiles)
        assertEquals(listOf("Track", "GNSS"), s.status.map { it.label })
        assertEquals("Tor Poznań", s.status.first().value)
        assertEquals(-0.34, requireNotNull(s.deltaSeconds), 1e-9)
        assertEquals(112_410L, s.bestMillis)
        assertEquals("3", s.tiles.last().value)
        assertEquals(listOf("S1", "S2"), s.sectors.map { it.name })
    }

    @Test
    fun alertsWhenNotRecordingOrGnssLost() {
        assertEquals(AlertLevel.Warning, liveTrackState(RecorderState(starting = true), tiles).alert?.level)
        val stopped = RecorderState(connected = true, error = "Storage full. The session is saved.")
        val failed = liveTrackState(stopped, tiles)
        assertEquals("Not recording", failed.alert?.title)
        assertEquals("Storage full. The session is saved.", failed.alert?.detail)
        val noFix = RecorderState(connected = true, live = live.copy(lastFixAgeMicros = 3_000_000))
        val lost = liveTrackState(noFix, tiles)
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
