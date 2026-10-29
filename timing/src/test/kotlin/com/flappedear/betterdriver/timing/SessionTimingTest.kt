@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Track detection at session start feeding live timing (FBBD-55, FBBD-12). */
class SessionTimingTest {
    private val gnss = FakeGnss()
    private val center = GeoPoint(gnss.centerLat, gnss.centerLon)
    private val gate = gnss.startFinish
    private val lined = Track(
        id = "ring", name = "Fake ring", center = center,
        startFinish = TrackLine(GeoPoint(gate.lat1, gate.lon1), GeoPoint(gate.lat2, gate.lon2)),
    )
    private val circuits = CircuitList(1, "test", listOf(Circuit("Q1", "Ring circuit", "PL", center)))

    /** Three and a half laps of 40 s at 25 Hz. */
    private fun drive(timing: SessionTiming) {
        for (n in 0L..(25 * 140)) timing.onSamples(SampleSet(n * 40_000, gnss.fix(n)))
    }

    @Test
    fun `a saved track with lines is timed`() {
        val detected = mutableListOf<TrackDetection>()
        var reads = 0
        val timing = SessionTiming({ reads++; listOf(lined) }, circuits) { detected += it }
        drive(timing)
        assertEquals(1, reads, "tracks are read once, at the first fix")
        val d = assertIs<TrackDetection.Saved>(detected.single())
        assertEquals("Fake ring", d.placeName)
        val state = assertNotNull(timing.state)
        assertEquals(40_000L, state.bestMillis)
        assertEquals(40_000L, state.lastMillis)
    }

    @Test
    fun `each lap is reported with the predicted lap time shown before its line`() {
        val lines = mutableListOf<String>()
        val timing = SessionTiming({ listOf(lined) }, circuits, onLap = { lap, predicted ->
            lines += lapLineText(lap, predicted)
        })
        drive(timing)
        val timed = lines.filter { "kind=Timed" in it }
        assertEquals(3, timed.size, lines.toString())
        // The first timed lap has no best lap to predict from; the next ones, at the same pace, predict it.
        assertEquals("time_ms=40000 predicted_ms=none", timed.first().substringAfter("kind=Timed "))
        for (line in timed.drop(1)) {
            val predicted = line.substringAfter("predicted_ms=").toLong()
            assertTrue(abs(predicted - 40_000) <= 40, line)
        }
    }

    @Test
    fun `a saved track without lines is named, and timed once the ring gets a provisional line`() {
        val timing = SessionTiming({ listOf(lined.copy(startFinish = null)) }, circuits)
        drive(timing)
        assertIs<TrackDetection.Saved>(timing.detection)
        // The ring is a loop driven 3.5 times (FBBD-335): the line goes on it, and the saved track keeps its id.
        assertEquals("ring", timing.provisional?.id)
        val state = assertNotNull(timing.state)
        assertTrue(abs(40_000L - assertNotNull(state.bestMillis)) <= 50, state.toString())
    }

    @Test
    fun `with no saved track the directory names the place`() {
        val timing = SessionTiming({ emptyList() }, circuits)
        // One lap: not yet a loop driven again, so no line and no timing.
        for (n in 0L..(25 * 40)) timing.onSamples(SampleSet(n * 40_000, gnss.fix(n)))
        assertEquals("Ring circuit", timing.detection?.placeName)
        assertNull(timing.provisional)
        assertNull(timing.state)
        // Laps after it get a provisional line named after the circuit (FBBD-335).
        for (n in (25L * 40 + 1)..(25 * 140)) timing.onSamples(SampleSet(n * 40_000, gnss.fix(n)))
        assertEquals("Ring circuit", timing.provisional?.name)
        assertNotNull(timing.state)
    }

    @Test
    fun `waits for a real position`() {
        val timing = SessionTiming({ listOf(lined) }, circuits)
        timing.onSamples(SampleSet(0, listOf(ChannelValue(GnssChannels.SATS.id, 3.0))))
        timing.onSamples(
            SampleSet(1, listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))),
        )
        assertNull(timing.detection)
        timing.onSamples(SampleSet(2, gnss.fix(0)))
        assertIs<TrackDetection.Saved>(timing.detection)
    }

    @Test
    fun `no-fix samples during the outing do not count`() {
        val timing = SessionTiming({ listOf(lined) }, circuits)
        val noFix = listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))
        for (n in 0L..(25 * 140)) {
            // A dropout every 2 s: the jumps to 0, 0 and back must not cross the line.
            timing.onSamples(SampleSet(n * 40_000, if (n % 50 == 25L) noFix else gnss.fix(n)))
        }
        val state = assertNotNull(timing.state)
        assertEquals(40_000L, state.bestMillis)
        assertEquals(40_000L, state.lastMillis)
    }
}
