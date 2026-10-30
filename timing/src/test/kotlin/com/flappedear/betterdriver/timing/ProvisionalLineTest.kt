@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A provisional start/finish where there is no line (FBBD-335): the demo circuit driven with its
 * track hidden, as the first session at a new track such as Moto Park Kraków on 8 Nov.
 */
class ProvisionalLineTest {
    private val gnss = DemoCircuit(startUtcMillis = 1_790_000_000_000L)
    private val center = DemoCircuit.track.center

    /** One lap of the timing a session at the demo circuit gets, as text: number, kind, time, prediction. */
    private class Logged(val number: Int, val kind: LapKind, val millis: Long, val predicted: Long?)

    private class Run(val timing: SessionTiming, val laps: List<Logged>, val made: List<Track>)

    /** [minutes] of the demo circuit at 25 Hz through a [SessionTiming] that knows [saved]. */
    private fun drive(minutes: Int, saved: List<Track> = emptyList(), circuits: CircuitList = CircuitList.Empty): Run {
        val laps = mutableListOf<Logged>()
        val made = mutableListOf<Track>()
        val timing = SessionTiming({ saved }, circuits, onLap = { lap, predicted ->
            laps += Logged(lap.number, lap.kind, lap.timeMillis, predicted)
        }, onProvisional = { made += it })
        for (n in 0L until minutes * 60L * gnss.rateHz) {
            timing.onSamples(SampleSet(n * 1_000_000L / gnss.rateHz, gnss.fix(n)))
        }
        return Run(timing, laps, made)
    }

    /** The laps the demo circuit's own track gives the same drive. */
    private fun ownLaps(minutes: Int) = timeSession(
        DemoCircuit.track,
        (0L until minutes * 60L * gnss.rateHz).asSequence().mapNotNull { n ->
            SampleSet(n * 1_000_000L / gnss.rateHz, gnss.fix(n)).toFix()
        },
    ).laps.filter { it.kind == LapKind.Timed }

    @Test
    fun `with no track there, the loop gets a line and its laps match the track's own`() {
        val run = drive(15)
        val made = run.made.single()
        assertTrue(made.timeable)
        assertEquals(made, run.timing.provisional)
        assertIs<TrackDetection.Unknown>(run.timing.detection)
        val timed = run.laps.filter { it.kind == LapKind.Timed }
        val own = ownLaps(15)
        // The line is elsewhere on the lap, so one lap may fall at either end.
        assertTrue(abs(timed.size - own.size) <= 1, "${timed.size} laps, the track's own line gives ${own.size}")
        // Each lap sums the same corners from another point on the circuit: the same pace within tenths.
        val median = timed.map { it.millis }.sorted()[timed.size / 2]
        val ownMedian = own.map { it.timeMillis }.sorted()[own.size / 2]
        assertTrue(abs(median - ownMedian) <= 500, "median $median ms, the track's own $ownMedian ms")
        assertEquals(timed.map { it.number }.sorted(), timed.map { it.number })
    }

    @Test
    fun `the line comes within the first laps, earlier laps are timed then, and later ones live`() {
        val run = drive(15)
        val timed = run.laps.filter { it.kind == LapKind.Timed }
        // Replayed laps had no prediction on screen; live ones after the first timed lap do.
        assertNull(timed.first().predicted)
        val live = timed.filter { it.predicted != null }
        assertTrue(live.size >= timed.size - 3, "${live.size} of ${timed.size} laps predicted live")
        for (lap in live) assertTrue(abs(assertNotNull(lap.predicted) - lap.millis) < 2_000, "lap ${lap.number}")
        val state = assertNotNull(run.timing.state)
        assertNotNull(state.bestMillis)
        assertNotNull(state.deltaSeconds)
    }

    @Test
    fun `a track saved without its line gets it, so saving the track keeps its id`() {
        val lineless = DemoCircuit.track.copy(
            id = "kraków", name = "Moto Park", startFinish = null, sectorLines = emptyList(),
        )
        val run = drive(6, saved = listOf(lineless))
        assertIs<TrackDetection.Saved>(run.timing.detection)
        val made = run.made.single()
        assertEquals("kraków", made.id)
        assertEquals("Moto Park", made.name)
        assertTrue(run.laps.count { it.kind == LapKind.Timed } >= 2, "${run.laps.size} laps")
    }

    @Test
    fun `a directory circuit names the provisional track`() {
        val circuits = CircuitList(1, "test", listOf(Circuit("Q1", "Moto Park Kraków", "PL", center)))
        val run = drive(6, circuits = circuits)
        assertEquals("Moto Park Kraków", run.made.single().name)
    }

    @Test
    fun `a saved track with its line is timed with it, never a provisional one`() {
        val run = drive(6, saved = listOf(DemoCircuit.track))
        assertTrue(run.made.isEmpty())
        assertNull(run.timing.provisional)
        assertTrue(run.laps.isNotEmpty())
    }

    @Test
    fun `a road drive that never closes a loop gets no line`() {
        val timing = SessionTiming({ emptyList() }, CircuitList.Empty)
        // 30 min due east at 90 km/h, then back west: the same road the other way is no loop.
        val perDegLon = 111_320.0 * cos(Math.toRadians(center.lat))
        for (n in 0L until 25L * 60 * 60) {
            val s = n / 25.0
            val east = if (s < 1_800) s * 25 else (3_600 - s) * 25
            timing.onSamples(
                SampleSet(
                    n * 40_000,
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, center.lat + (if (s < 1_800) 0.0 else 0.0001)),
                        ChannelValue(GnssChannels.LON.id, center.lon + east / perDegLon),
                    ),
                ),
            )
        }
        assertNull(timing.provisional)
        assertNull(timing.state)
    }

    @Test
    fun `looking for the loop stays cheap on a long drive`() {
        // An hour with no line: one search each 10 s over the last 20 min, on the listener thread.
        val timing = SessionTiming({ emptyList() }, CircuitList.Empty)
        val perDegLon = 111_320.0 * cos(Math.toRadians(center.lat))
        val started = TimeSource.Monotonic.markNow()
        for (n in 0L until 25L * 60 * 60) {
            val east = n / 25.0 * 25
            timing.onSamples(
                SampleSet(
                    n * 40_000,
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, center.lat + 0.001 * kotlin.math.sin(east / 500)),
                        ChannelValue(GnssChannels.LON.id, center.lon + east / perDegLon),
                    ),
                ),
            )
        }
        val took = started.elapsedNow()
        assertNull(timing.provisional)
        println("an hour of road with no line: $took")
        assertTrue(took.inWholeSeconds < 20, "took $took")
    }

    @Test
    fun `the provisional line is logged with its ends and the track`() {
        val made = drive(6).made.single()
        val text = TrackEvent.provisionalText(made)
        val line = assertNotNull(made.startFinish)
        val ends = "${line.left.lat} ${line.left.lon} ${line.right.lat} ${line.right.lon}"
        assertTrue(text.startsWith("line $ends at "), text)
        assertTrue(text.endsWith(made.displayName), text)
    }
}
