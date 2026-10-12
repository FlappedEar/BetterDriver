@file:Suppress("MagicNumber") // Tolerances from the Jira criteria.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.VboFixture
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.laps.detectLaps
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-61 and FBBD-12 on Arek's RaceChrono session at Silesia Ring (27 Sep 2026), timed against
 * RaceChrono's own start/finish and split lines from the file. The file has no lap table of
 * RaceChrono's, so the laps are checked against two other readings of the same lines: the analysis's
 * gate detection (FlappedEar Telemetry's, ported, FBBD-4) on the imported session, and FlappedEar
 * Telemetry's own reading of the file (VboFixture: float32 values, RaceChrono's gates).
 */
class LapTimingReplayTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val track = RealCorpus.silesiaTrack()

    private fun silesia(): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging"), RealCorpus.SILESIA, listOf(track)).id)

    /** The live lap timer's laps, replayed from the session as the track screen timed them. */
    private fun appLaps(data: SessionData, at: Track = track): List<Lap> =
        replayLaps(data.id, data.sampleSets, at).laps.map { it.lap }

    /** Start times (s) of the passes the analysis's gate detection finds through [line] alone. */
    private fun passes(data: SessionData, line: TrackLine): List<Double> {
        val session = telemetrySessionOf(data, line)
        return detectLaps(session, session.timingGates.single()).acceptedPasses.map { it.telemetryTime }
    }

    @Test
    fun `laps match the analysis and FlappedEar Telemetry to 0_02 s, none missed or phantom (FBBD-61)`() {
        val data = silesia()
        val app = appLaps(data)
        val timed = app.filter { it.kind == LapKind.Timed }
        // The session: an out lap from the paddock, then seven timed laps to the line, the last one in.
        assertEquals(LapKind.Out, app.first().kind)
        assertEquals(7, timed.size)
        assertEquals(app.size, timed.size + 1)
        val origin = data.sampleSets.first().tMicros

        val analysis = deriveSourceLapSession(telemetrySessionOf(data, track)).timedLaps
        val telemetry = deriveSourceLapSession(VboFixture.parse(RealCorpus.text(RealCorpus.SILESIA))).timedLaps
        for ((name, other) in listOf("analysis" to analysis, "FlappedEar Telemetry" to telemetry)) {
            assertEquals(timed.size, other.size, "$name: lap count")
            timed.zip(other).forEach { (a, b) ->
                val start = (a.startMicros - origin) / 1e6
                val what = "$name: lap ${a.number} starts at $start, ${b.startTelemetryTime}"
                assertTrue(abs(start - b.startTelemetryTime) <= 0.02, what)
                assertTrue(
                    abs(a.timeMicros / 1e6 - b.durationSeconds) <= 0.02,
                    "$name: lap ${a.number} ${a.timeMicros / 1e6} s, ${b.durationSeconds} s",
                )
            }
        }
    }

    @Test
    fun `every sector of every timed lap matches the line passes to 0_05 s (FBBD-61)`() {
        val data = silesia()
        val origin = data.sampleSets.first().tMicros
        val lines = listOf(track.startFinish!!) + track.sectorLines
        val passes = lines.map { passes(data, it) }
        val timed = appLaps(data).filter { it.kind == LapKind.Timed }
        for (lap in timed) {
            assertTrue(lap.allSectors, "lap ${lap.number}: every sector timed")
            // Sector i runs from line i to line i + 1, the last one back to the start/finish line.
            var t = (lap.startMicros - origin) / 1e6
            lap.sectorMicros.forEachIndexed { i, sector ->
                val nextLine = passes[(i + 1) % lines.size]
                val end = nextLine.firstOrNull { it > t + 1.0 } ?: error("lap ${lap.number}: no pass of line ${i + 1}")
                val seconds = sector!! / 1e6
                val what = "lap ${lap.number} S${i + 1}: $seconds s, passes ${end - t} s"
                assertTrue(abs(seconds - (end - t)) <= 0.05, what)
                t = end
            }
        }
    }

    @Test
    fun `the predicted lap time at the line is within 0_1 s of the lap time on every lap (FBBD-12)`() {
        val data = silesia()
        val shown = mutableListOf<Pair<Lap, Long?>>()
        val timing = SessionTiming({ listOf(track) }, CircuitList.Empty, onLap = { lap, ms -> shown += lap to ms })
        data.sampleSets.forEach(timing::onSamples)
        val timed = shown.filter { it.first.kind == LapKind.Timed }
        assertEquals(7, timed.size)
        // The first timed lap has no best lap before it to predict from.
        assertEquals(null, timed.first().second)
        val predicted = timed.drop(1)
        predicted.forEach { (lap, millis) ->
            val error = abs(assertNotNull(millis, "lap ${lap.number}") - lap.timeMillis)
            assertTrue(error <= 100, "lap ${lap.number}: predicted $millis ms at the line, lap ${lap.timeMillis} ms")
        }
    }

    @Test
    fun `point-to-point runs on the road drives match the analysis to 0_05 s (FBBD-61)`() {
        for (name in listOf(RealCorpus.COMMUTE_1036, RealCorpus.COMMUTE_0422)) {
            val road = assertNotNull(RealCorpus.roadTrack(name))
            val data = store.read(RealCorpus.import(store, File(tmp, "staging"), name, listOf(road)).id)
            val runs = appLaps(data, road)
            val analysis = deriveSourceLapSession(telemetrySessionOf(data, road)).timedLaps
            assertEquals(1, runs.size, name)
            assertEquals(runs.size, analysis.size, name)
            runs.zip(analysis).forEach { (a, b) ->
                val what = "$name: ${a.timeMicros / 1e6} s, ${b.durationSeconds} s"
                assertTrue(abs(a.timeMicros / 1e6 - b.durationSeconds) <= 0.05, what)
            }
        }
    }
}
