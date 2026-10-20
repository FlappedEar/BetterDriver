package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.setup.MaxMainTiles
import com.flappedear.betterdriver.setup.MaxSecondTiles
import com.flappedear.betterdriver.setup.TrackScreens
import com.flappedear.betterdriver.timing.live.LiveSector
import com.flappedear.betterdriver.timing.live.LiveTimingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LiveUiTest {
    private val tiles = TrackScreens(main = listOf("speed", "g", "lap_number"))
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
        receiverRateHz = 25,
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
        val s = liveTrackState(RecorderState(connected = true, live = live), tiles, true)
        assertNull(s.alert)
        assertEquals("12:34", s.recordingTime)
        assertEquals("25 Hz · 14 sats", s.status.single().value)
        assertEquals("142", s.tiles.first()?.value)
        assertEquals("–", s.tiles[1]?.value)
        assertNull(s.deltaSeconds)
    }

    @Test
    fun theFirstSecondOfFixesShowsTheReceiverRateNotAPartialCount() {
        // 2026-10-14: 8 fixes in the first second after the fix came, the RaceBox sending 25 a second.
        val first = live.copy(fixRateHz = 8, receiverRateHz = 25)
        fun shown(l: LiveSnapshot) =
            liveTrackState(RecorderState(connected = true, live = l), tiles, true).status.single().value
        assertEquals("25 Hz · 14 sats", shown(first))
        val unknown = live.copy(fixRateHz = 8, receiverRateHz = 0)
        assertEquals("8 Hz · 14 sats", shown(unknown))
    }

    @Test
    fun tilesFollowTheSessionChoice() {
        val withG = RecorderState(connected = true, live = live.copy(gCombined = 1.123))
        val s = liveTrackState(withG, tiles, true)
        assertEquals(listOf("Speed", "G", "Lap"), s.tiles.take(3).map { it?.label })
        assertEquals("1.12", s.tiles[1]?.value)
        assertEquals("every other slot is empty", List(MaxMainTiles - 3) { null }, s.tiles.drop(3))
        assertEquals(List(MaxSecondTiles) { null }, s.secondTiles)
        // No timing yet: the lap tile has no data; a receiver without G leaves the G tile out (UI-126 B).
        assertEquals(listOf(true, true, false), s.tiles.take(3).map { it?.hasData })
        assertEquals(false, liveTrackState(withG, tiles, false).tiles[1]?.hasData)
    }

    @Test
    fun carAndBodyTilesShowTheirValuesAndADashOnceStale() {
        val screens = TrackScreens(
            main = listOf("obd_coolant_temp", "obd_module_voltage", "heart_rate", "obd_oil_temp"),
            second = listOf("obd_lambda", "obd_rpm"),
        )
        val channels = mapOf(
            "obd_coolant_temp" to 91.6,
            "obd_module_voltage" to 14.08,
            "heart_rate" to Double.NaN,
            "obd_lambda" to 0.987,
            "obd_rpm" to 5840.4,
        )
        val s = liveTrackState(RecorderState(connected = true, live = live.copy(channels = channels)), screens, true)
        assertEquals(listOf("92", "14.1", "–", "–"), s.tiles.take(4).map { it?.value })
        assertEquals(
            listOf("Coolant temp", "Battery voltage", "Heart rate", "Oil temp"),
            s.tiles.take(4).map { it?.label },
        )
        assertEquals(listOf("°C", "V", "bpm", "°C"), s.tiles.take(4).map { it?.unit })
        // Oil temperature never came (the car does not answer 0x5C): left out. Heart rate stopped: a dash.
        assertEquals(listOf(true, true, true, false), s.tiles.take(4).map { it?.hasData })
        assertEquals(listOf("0.99", "5840"), s.secondTiles.take(2).map { it?.value })
    }

    @Test
    fun timingTilesAndTheTileList() {
        val timing = LiveTimingState(
            lapNumber = 3, lapMillis = 47_300, deltaSeconds = -0.34, predictedMillis = 112_070,
            lastMillis = 112_900, bestMillis = 112_410, theoreticalBestMillis = 111_800,
            sectors = emptyList(),
        )
        val state = RecorderState(
            connected = true,
            live = live.copy(channels = mapOf("obd_coolant_temp" to 92.0)),
            timing = timing,
        )
        val screens = TrackScreens(main = listOf("delta", "lap_time", "last_lap", "best_lap", "predicted_lap"))
        val s = liveTrackState(state, screens, true)
        assertEquals(listOf("−0.34", "0:47.30", "1:52.90", "1:52.41", "1:52.07"), s.tiles.take(5).map { it?.value })
        val groups = liveTileChoices(state, receiverHasG = false, receiver = "RaceBox Micro")
        assertEquals(
            listOf("Timing", "GPS · RaceBox Micro", "Car · OBD-II", "You · heart rate"),
            groups.map { it.title },
        )
        val car = groups[2].choices
        assertEquals("92 °C", car.single { it.id == "obd_coolant_temp" }.value)
        assertEquals(false, car.single { it.id == "obd_oil_temp" }.hasData)
        assertEquals(false, groups[1].choices.single { it.id == "g" }.hasData)
        assertEquals("142 km/h", groups[1].choices.single { it.id == "speed" }.value)
    }

    @Test
    fun aTileCanBeChangedOnlyWithTheCarStopped() {
        assertEquals(true, stopped(null))
        assertEquals(false, stopped(live))
        assertEquals(true, stopped(live.copy(speedKmh = 3.0)))
        assertEquals("no fix: nothing says the car moves", true, stopped(live.copy(lastFixAgeMicros = 5_000_000)))
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
        val timed =
            RecorderState(connected = true, live = live, place = "Tor Poznań", placeSaved = true, timing = timing)
        val s = liveTrackState(timed, tiles, true)
        assertEquals(listOf("Track", "GNSS"), s.status.map { it.label })
        assertEquals("Tor Poznań", s.status.first().value)
        assertEquals(-0.34, requireNotNull(s.deltaSeconds), 1e-9)
        assertEquals(112_410L, s.bestMillis)
        assertEquals(3, s.lapNumber)
        assertEquals(47_300L, s.lapMillis)
        val outLap = liveTrackState(timed.copy(timing = timing.copy(lapNumber = 0, lapMillis = null)), tiles, true)
        assertNull("no number before the first line crossing", outLap.lapNumber)
        assertEquals("3", s.tiles[2]?.value)
    }

    @Test
    fun circuitFromTheListIsNeutralAndASavedTrackWithoutLinesIsAmber() {
        val road = liveTrackState(RecorderState(connected = true, live = live, place = "Tor Kielce"), tiles, true)
        assertEquals(StatusItem("Near", "Tor Kielce", ok = true), road.status.first())
        val untimed = RecorderState(connected = true, live = live, place = "Tor Kielce", placeSaved = true)
        assertEquals(StatusItem("Track", "Tor Kielce", ok = false), liveTrackState(untimed, tiles, true).status.first())
    }

    @Test
    fun alertsWhenNotRecordingOrGnssLost() {
        assertEquals(AlertLevel.Warning, liveTrackState(RecorderState(starting = true), tiles, true).alert?.level)
        val stopped = RecorderState(connected = true, error = "Storage full. The session is saved.")
        val failed = liveTrackState(stopped, tiles, true)
        assertEquals("Not recording", failed.alert?.title)
        assertEquals("Storage full. The session is saved.", failed.alert?.detail)
        // A 25 Hz receiver is lost 1 s after its last fix (FBBD-64).
        val noFix = RecorderState(connected = true, live = live.copy(lastFixAgeMicros = 1_100_000))
        val lost = liveTrackState(noFix, tiles, true)
        assertEquals("GNSS signal lost", lost.alert?.title)
        assertEquals("Lost", lost.status.single().value)
        // The last speed before the loss is not shown as if it were current.
        assertEquals("–", lost.tiles.first()?.value)
        val late = RecorderState(connected = true, live = live.copy(lastFixAgeMicros = 900_000))
        val stillOk = liveTrackState(late, tiles, true)
        assertEquals(null, stillOk.alert)
        assertEquals("142", stillOk.tiles.first()?.value)
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
        assertEquals("BetterDriver-id.vbo", exportFileName(s, ExportFormat.Vbo))
        assertEquals("BetterDriver-id.csv", exportFileName(s, ExportFormat.Csv))
        assertEquals("BetterDriver-id.gpx", exportFileName(s, ExportFormat.Gpx))
    }
}
