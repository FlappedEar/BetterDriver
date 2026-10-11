@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.LiveTimingState
import com.flappedear.betterdriver.timing.live.TimingListener
import com.flappedear.betterdriver.timing.live.startFinishGate
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-60 and the live state the track screen shows. */
class LiveTimingTest {
    private val circuit = FakeCircuit()

    // Lap 1 is the short out lap, then laps 2 to 4 at these paces.
    private val paces = listOf(1.0, 1.05, 0.97)
    private val drive = circuit.drive(paces)
    private val passes = drive.lineTimesMicros.getValue(0.0)
    /** thirds[k]: the first sector line on lap k + 2. */
    private val thirds = drive.lineTimesMicros.getValue(1.0 / 3)

    /** Replays the drive; returns the state after every fix. */
    private fun replay(): List<LiveTimingState> {
        val live = LiveTiming(circuit.track())
        return drive.fixes.map { live.update(it) }
    }

    /** True time of lap [n] (2 to 4): passes[k] is the end of lap k + 1. */
    private fun lapTruth(n: Int) = passes[n - 1] - passes[n - 2]

    @Test
    fun `no delta until there is a best lap`() {
        val states = replay()
        val beforeSecondLapEnds = drive.fixes.indexOfFirst { it.tMicros > passes[1] } - 1
        states.take(beforeSecondLapEnds).forEach { assertNull(it.deltaSeconds) }
    }

    @Test
    fun `delta at the line is the true lap time difference`() {
        val states = replay()
        // Lap 3 (pace 1.05) against lap 2, and lap 4 (pace 0.97) against lap 3.
        for ((lap, against) in listOf(3 to 2, 4 to 3)) {
            val lastFixOfLap = drive.fixes.indexOfLast { it.tMicros < passes[lap - 1] }
            val s = states[lastFixOfLap]
            val truth = (lapTruth(lap) - lapTruth(against)) / 1e6
            val delta = assertNotNull(s.deltaSeconds, "delta on lap $lap")
            assertEquals(truth, delta, 0.010, "delta just before the line, lap $lap")
            val predicted = assertNotNull(s.predictedMillis)
            assertEquals(lapTruth(lap) / 1000, predicted.toDouble(), 10.0, "predicted lap time, lap $lap")
        }
    }

    @Test
    fun `delta part way round matches the true gap`() {
        val states = replay()
        // At the first sector line of lap 3 the true gap is lap 3's time to there minus lap 2's.
        val truth = ((thirds[1] - passes[1]) - (thirds[0] - passes[0])) / 1e6
        val i = drive.fixes.indexOfFirst { it.tMicros >= thirds[1] }
        val delta = assertNotNull(states[i].deltaSeconds)
        assertEquals(truth, delta, 0.010)
        assertTrue(delta < 0, "lap 3 is faster")
    }

    @Test
    fun `last, best and sector colours`() {
        val states = replay()
        val end = states.last()
        assertEquals(lapTruth(4) / 1000, assertNotNull(end.lastMillis).toDouble(), 5.0)
        assertEquals(lapTruth(3) / 1000, assertNotNull(end.bestMillis).toDouble(), 5.0)
        assertEquals(5, end.lapNumber)
        // Mid lap 4 (slower than lap 3, the best then): its first sector shows a loss.
        val i = drive.fixes.indexOfFirst { it.tMicros >= thirds[2] }
        val s1 = states[i + 1].sectors[0]
        assertTrue(assertNotNull(s1.deltaVsBestLapSeconds) > 0)
        assertTrue(states[i + 1].sectors[1].current)
    }

    @Test
    fun `re-timing a session gives the same laps`() {
        val book = timeSession(circuit.track(), drive.fixes.asSequence())
        assertEquals(4, book.laps.size)
        assertEquals(lapTruth(3), assertNotNull(book.best).timeMicros.toDouble(), 5_000.0)
    }

    @Test
    fun `moving the start line recomputes the laps`() {
        val moved = circuit.track().copy(startFinish = circuit.lineAt(circuit.length / 2))
        val book = timeSession(moved, drive.fixes.asSequence())
        assertTrue(book.laps.isNotEmpty())
        assertTrue(book.laps.drop(1).all { it.timeMicros > 30_000_000 })
    }

    @Test
    fun `times the recorder's fake GNSS through a recorder listener`() {
        val gnss = FakeGnss()
        val gate = gnss.startFinish
        val track = Track(
            id = "fake-gnss", name = "Fake GNSS", center = GeoPoint(gnss.centerLat, gnss.centerLon),
            startFinish = TrackLine(GeoPoint(gate.lat1, gate.lon1), GeoPoint(gate.lat2, gate.lon2)),
        )
        assertEquals(gate.copy(name = "Start / Finish"), track.startFinishGate())
        var last: LiveTimingState? = null
        val listener = TimingListener(track) { last = it }
        // Three and a half laps of 40 s at 25 Hz, plus a set with no position.
        for (n in 0L..(25 * 140)) {
            listener.onSamples(SampleSet(n * 40_000, gnss.fix(n)))
        }
        listener.onSamples(SampleSet(140 * 1_000_000L + 1, listOf(ChannelValue(4, 100.0))))
        val laps = listener.timing.book.laps
        assertEquals(4, laps.size)
        laps.drop(1).forEach { assertEquals(40_000L, it.timeMillis) }
        assertEquals(40_000L, assertNotNull(last).bestMillis)
    }
}
