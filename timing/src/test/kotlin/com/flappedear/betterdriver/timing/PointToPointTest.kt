@file:Suppress("MagicNumber", "NestedBlockDepth") // Test geometry and simulation.

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.edit.lineRefs
import com.flappedear.betterdriver.timing.edit.withLine
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.DefaultMinLapSeconds
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-155: point-to-point tracks. A made-up hill climb runs east along a straight road: start at
 * x = 100 m, a sector line at 600 m, finish at 1,100 m. The drive back runs west 8 m to the north,
 * still across every line, but the wrong way. The drive is simulated in 0.1 ms steps with the speed
 * always changing, which gives the true crossing times; fixes are taken at 25 Hz.
 */
class PointToPointTest {
    private val origin = GeoPoint(49.30, 19.95)
    private val frame = LocalFrame(origin)

    /** A line across the road at [x], crossed forwards driving east. */
    private fun lineAt(x: Double) = TrackLine(frame.toGeo(Xy(x, 15.0)), frame.toGeo(Xy(x, -15.0)))

    private val startX = 100.0
    private val sectorX = 600.0
    private val finishX = 1_100.0
    private val marks = listOf(startX, sectorX, finishX)

    private val hill = Track(
        id = "hill",
        name = "Hill climb",
        center = origin,
        startFinish = lineAt(startX),
        sectorLines = listOf(lineAt(sectorX)),
        kind = TrackKind.PointToPoint,
        finish = lineAt(finishX),
    )

    /** Drive to [to] at [pace] (1.0 = base speed). */
    private class Leg(val to: Xy, val pace: Double)

    /** Fixes, and the true time of every eastward pass of each mark (x of a line). */
    private class Drive(val fixes: List<Fix>, val passes: Map<Double, List<Double>>)

    private fun drive(legs: List<Leg>, from: Xy = Xy(0.0, 0.0), rateHz: Int = 25): Drive {
        val dt = 1e-4
        val startMicros = 2_000_000.0
        var p = from
        var t = 0.0
        var nextFix = 0.0
        val fixes = mutableListOf<Fix>()
        val passes = marks.associateWith { mutableListOf<Double>() }
        fun fix() {
            val g = frame.toGeo(p)
            fixes += Fix((startMicros + t * 1e6).roundToLong(), g.lat, g.lon)
            nextFix += 1.0 / rateHz
        }
        for (leg in legs) {
            while (true) {
                if (t >= nextFix - 1e-9) fix()
                val d = leg.to - p
                val left = d.length()
                if (left < 1e-9) break
                // Speed by place on the road, so runs at the same pace drive alike.
                val v = (25.0 + 8.0 * sin(p.x / 37.0) + 3.0 * cos(p.x / 11.0)) * leg.pace
                val step = minOf(v * dt, left)
                val q = p + d * (step / left)
                for (m in marks) {
                    if (p.x < m && q.x >= m) {
                        val at = t + step / v * (m - p.x) / (q.x - p.x)
                        passes.getValue(m) += startMicros + at * 1e6
                    }
                }
                t += step / v
                p = q
            }
        }
        return Drive(fixes, passes)
    }

    /** Up the hill at [pace] and back down to the paddock at a crawl. */
    private fun runAndBack(pace: Double) = listOf(
        Leg(Xy(1_200.0, 0.0), pace),
        Leg(Xy(1_200.0, 8.0), 0.2),
        Leg(Xy(0.0, 8.0), 0.6),
        Leg(Xy(0.0, 0.0), 0.2),
    )

    private val paces = listOf(1.0, 1.06, 0.97)
    private val threeRuns = drive(paces.flatMap(::runAndBack))
    private val starts = threeRuns.passes.getValue(startX)
    private val sectors = threeRuns.passes.getValue(sectorX)
    private val finishes = threeRuns.passes.getValue(finishX)

    /** True time of run [n], from 1. */
    private fun runTruth(n: Int) = finishes[n - 1] - starts[n - 1]

    private fun events(track: Track, fixes: List<Fix>): List<LapEvent> {
        val timer = LapTimer(track)
        return fixes.flatMap(timer::update)
    }

    @Test
    fun `each run from start to finish is a timed lap and the drive back is not timed`() {
        val events = events(hill, threeRuns.fixes)
        val laps = events.filterIsInstance<LapEvent.LapDone>().map { it.lap }
        assertEquals(listOf(1, 2, 3), laps.map { it.number })
        assertTrue(laps.all { it.kind == LapKind.Timed })
        for (lap in laps) {
            val n = lap.number
            assertEquals(runTruth(n), lap.timeMicros.toDouble(), 2_000.0, "run $n")
            assertEquals(starts[n - 1], lap.startMicros.toDouble(), 2_000.0)
            val s1 = assertNotNull(lap.sectorMicros[0]).toDouble()
            val s2 = assertNotNull(lap.sectorMicros[1]).toDouble()
            assertEquals(sectors[n - 1] - starts[n - 1], s1, 2_000.0, "run $n sector 1")
            assertEquals(finishes[n - 1] - sectors[n - 1], s2, 2_000.0, "run $n sector 2")
        }
        assertEquals(3, events.filterIsInstance<LapEvent.RunStarted>().size)
        // Sector events only come during runs: one per line per run.
        assertEquals(6, events.filterIsInstance<LapEvent.SectorDone>().size)
    }

    @Test
    fun `an aborted run is dropped and the next start begins it again`() {
        // Up past the sector line, back down without finishing, then a full run.
        val legs = listOf(Leg(Xy(700.0, 0.0), 1.0), Leg(Xy(700.0, 8.0), 0.2), Leg(Xy(0.0, 8.0), 0.6)) +
            Leg(Xy(0.0, 0.0), 0.2) + runAndBack(1.0)
        val d = drive(legs)
        val events = events(hill, d.fixes)
        val laps = events.filterIsInstance<LapEvent.LapDone>().map { it.lap }
        assertEquals(1, laps.size)
        val run = laps.single()
        assertEquals(1, run.number, "the aborted run's number is used again")
        val start = d.passes.getValue(startX)[1]
        assertEquals(d.passes.getValue(finishX)[0] - start, run.timeMicros.toDouble(), 2_000.0)
        assertEquals(listOf(1, 1), events.filterIsInstance<LapEvent.RunStarted>().map { it.lapNumber })
    }

    @Test
    fun `a run that never reaches the finish gives no lap`() {
        val d = drive(listOf(Leg(Xy(900.0, 0.0), 1.0)))
        val timer = LapTimer(hill)
        val events = d.fixes.flatMap(timer::update)
        assertTrue(events.none { it is LapEvent.LapDone })
        assertTrue(timer.running)
        assertEquals(1, timer.lapNumber)
        assertTrue(timeSession(hill, d.fixes.asSequence()).laps.isEmpty())
    }

    @Test
    fun `starting past the start line waits for the next run`() {
        // The session starts between the lines: the finish is not a run's end, nothing is timed.
        val d = drive(listOf(Leg(Xy(1_200.0, 0.0), 1.0)), from = Xy(300.0, 0.0))
        val timer = LapTimer(hill)
        assertTrue(d.fixes.flatMap(timer::update).isEmpty())
        assertFalse(timer.running)
        assertEquals(0, timer.lapNumber)
    }

    @Test
    fun `live timing shows runs, nothing between them, and the delta to the best run`() {
        val live = LiveTiming(hill)
        assertTrue(live.state.pointToPoint)
        val states = threeRuns.fixes.map(live::update)
        val fixTimes = threeRuns.fixes.map { it.tMicros }
        fun at(t: Double) = states[fixTimes.indexOfLast { it < t }]

        // Before the first run, and on the drive back: no lap time, no delta.
        val waiting = at(starts[0])
        assertNull(waiting.lapMillis)
        assertEquals(0, waiting.lapNumber)
        assertTrue(waiting.sectors.none { it.current })
        val back = at(starts[1])
        assertNull(back.lapMillis)
        assertNull(back.deltaSeconds)
        assertEquals(1, back.lapNumber)
        assertEquals(runTruth(1) / 1000, assertNotNull(back.lastMillis).toDouble(), 5.0)
        // Run 2's sectors (faster than run 1's) stay on the strip all the way back down.
        val backAgain = at(starts[2])
        assertNull(backAgain.lapMillis)
        assertTrue(backAgain.sectors.all { assertNotNull(it.deltaVsBestLapSeconds) < 0 && !it.current })

        // During run 1 the time runs, with no delta yet.
        val firstRun = at(sectors[0])
        assertNotNull(firstRun.lapMillis)
        assertNull(firstRun.deltaSeconds)
        assertEquals(1, firstRun.lapNumber)

        // Runs 2 and 3 against the best run before them, just before the finish.
        for ((run, against) in listOf(2 to 1, 3 to 2)) {
            val s = at(finishes[run - 1])
            assertEquals(run, s.lapNumber)
            val truth = (runTruth(run) - runTruth(against)) / 1e6
            assertEquals(truth, assertNotNull(s.deltaSeconds, "delta on run $run"), 0.010)
            assertEquals(runTruth(run) / 1000, assertNotNull(s.predictedMillis).toDouble(), 10.0)
        }
        // Part way up run 2, at the sector line, the delta is the true gap there.
        val mid = states[fixTimes.indexOfFirst { it >= sectors[1] }]
        val gap = ((sectors[1] - starts[1]) - (sectors[0] - starts[0])) / 1e6
        assertEquals(gap, assertNotNull(mid.deltaSeconds), 0.010)
        assertTrue(abs(gap) > 0.1)

        val end = states.last()
        assertEquals(runTruth(2) / 1000, assertNotNull(end.bestMillis).toDouble(), 5.0)
        assertEquals(3, end.lapNumber)
        assertNull(end.lapMillis)
    }

    @Test
    fun `the delta starts again after an aborted run`() {
        val legs = runAndBack(1.0) + listOf(Leg(Xy(700.0, 0.0), 1.0), Leg(Xy(700.0, 8.0), 0.2)) +
            listOf(Leg(Xy(0.0, 8.0), 0.6), Leg(Xy(0.0, 0.0), 0.2)) + runAndBack(1.05)
        val d = drive(legs)
        val live = LiveTiming(hill)
        val states = d.fixes.map(live::update)
        val s = d.passes.getValue(startX)
        val f = d.passes.getValue(finishX)
        val book = live.book
        assertEquals(2, book.laps.size)
        assertEquals(listOf(1, 2), book.laps.map { it.number })
        val lastBeforeFinish = states[d.fixes.indexOfLast { it.tMicros < f[1] }]
        val truth = ((f[1] - s[2]) - (f[0] - s[0])) / 1e6
        assertEquals(truth, assertNotNull(lastBeforeFinish.deltaSeconds), 0.010)
    }

    /** A session as the recorder stores it. */
    private fun session(fixes: List<Fix>) = fixes.map {
        SampleSet(
            it.tMicros,
            listOf(
                ChannelValue(GnssChannels.LAT.id, it.lat),
                ChannelValue(GnssChannels.LON.id, it.lon),
                ChannelValue(GnssChannels.SPEED_KMH.id, 90.0),
            ),
        )
    }

    @Test
    fun `replay gives the runs as laps, each with its path from start to finish`() {
        val laps = replayLaps("hill-1", session(threeRuns.fixes), hill)
        assertEquals(listOf(1, 2, 3), laps.laps.map { it.lap.number })
        for (trace in laps.laps) {
            val n = trace.lap.number
            assertEquals(runTruth(n), trace.lap.timeMicros.toDouble(), 2_000.0)
            assertEquals(finishX - startX, trace.lengthMeters, 1.0, "run $n is up the hill only")
            assertEquals(0L, trace.points.first().lapMicros)
            assertEquals(trace.lap.timeMicros, trace.points.last().lapMicros)
        }
        assertEquals(2, assertNotNull(laps.best).lap.number)
        val align = LapAlignment(laps.laps[0], laps.laps[1])
        assertEquals(runTruth(2) - runTruth(1), align.deltaMicrosAt(laps.laps[0].lap.timeMicros).toDouble(), 2_000.0)
    }

    @Test
    fun `the export has the runs and no open lap for the drive back`() {
        val runs = replayLaps("hill-1", session(threeRuns.fixes), hill).exportLaps()
        assertEquals(listOf(1, 2, 3), runs.map { it.number })
        assertTrue(runs.none { it.endMicros == Long.MAX_VALUE })
        // A circuit keeps the lap still being driven at the end.
        val laps = replayLaps("hill-1", session(threeRuns.fixes), hill.copy(kind = TrackKind.Circuit)).exportLaps()
        val open = laps.last()
        assertEquals(Long.MAX_VALUE, open.endMicros)
        assertEquals(laps[laps.size - 2].endMicros, open.startMicros)
    }

    @Test
    fun `the video overlay shows each run, and no run between runs or after the last`() {
        val sets = session(threeRuns.fixes)
        val laps = replayLaps("hill-1", sets, hill)
        val data = OverlayData(sets, laps)
        assertTrue(data.pointToPoint)

        // Part way up run 1: the run, its time and the delta to run 2, the best.
        val inRun = assertNotNull(data.at(sectors[0].roundToLong()).lap)
        assertEquals(1, inRun.number)
        assertEquals(LapKind.Timed, inRun.kind)
        assertFalse(inRun.isBest)
        assertEquals(sectors[0] - starts[0], inRun.lapMicros.toDouble(), 2_000.0)
        val gap = (sectors[0] - starts[0]) - (sectors[1] - starts[1])
        assertEquals(gap, assertNotNull(inRun.deltaMicros).toDouble(), 30_000.0)
        assertTrue(assertNotNull(data.at(sectors[1].roundToLong()).lap).isBest)

        // Before the first run, on the drive back and after the last finish: no run, as on the track screen.
        assertNull(data.at(starts[0].roundToLong() - 1_000_000).lap)
        assertNull(data.at(((finishes[0] + starts[1]) / 2).roundToLong()).lap)
        assertNull(data.at(finishes[2].roundToLong() + 2_000_000).lap)
        assertNotNull(data.at(threeRuns.fixes.last().tMicros).position, "the car is still on the map")

        // A circuit keeps counting the lap being driven after the last line.
        val circuit = replayLaps("hill-1", sets, hill.copy(kind = TrackKind.Circuit))
        val open = OverlayData(sets, circuit)
        assertFalse(open.pointToPoint)
        assertNotNull(open.at(threeRuns.fixes.last().tMicros).lap)
    }

    @Test
    fun `replay leaves out an aborted run`() {
        val legs = listOf(Leg(Xy(700.0, 0.0), 1.0), Leg(Xy(700.0, 8.0), 0.2), Leg(Xy(0.0, 8.0), 0.6)) +
            Leg(Xy(0.0, 0.0), 0.2) + runAndBack(1.0)
        val laps = replayLaps("hill-2", session(drive(legs).fixes), hill)
        assertEquals(1, laps.laps.size)
        assertEquals(finishX - startX, laps.laps.single().lengthMeters, 1.0)
    }

    @Test
    fun `the same drive on a circuit track times the drive back too`() {
        // Without the kind, the finish line is ignored and laps run start to start.
        val circuit = hill.copy(kind = TrackKind.Circuit)
        val laps = events(circuit, threeRuns.fixes).filterIsInstance<LapEvent.LapDone>().map { it.lap }
        assertEquals(3, laps.size)
        assertEquals(starts[1] - starts[0], laps[1].timeMicros.toDouble(), 2_000.0)
    }

    @Test
    fun `a point-to-point track needs both lines to be timed`() {
        assertTrue(hill.timeable)
        assertFalse(hill.copy(finish = null).timeable)
        assertFalse(hill.copy(startFinish = null).timeable)
        assertTrue(hill.copy(finish = null, kind = TrackKind.Circuit).timeable)
        assertFailsWith<IllegalArgumentException> { LapTimer(hill.copy(finish = null)) }
    }

    @Test
    fun `old track files read as circuits`() {
        // A track file as builds before FBBD-155 wrote it: no kind, no finish.
        val old = """
            {
                "format": "betterdriver-track",
                "version": 1,
                "track": {
                    "id": "tor-poznan",
                    "name": "Tor Poznań",
                    "center": { "lat": 52.4178, "lon": 16.8058 },
                    "country": "PL",
                    "layout": "",
                    "radiusMeters": 2000.0,
                    "startFinish": {
                        "left": { "lat": 52.4170, "lon": 16.8050 },
                        "right": { "lat": 52.4171, "lon": 16.8051 }
                    },
                    "sectorLines": [],
                    "pitEntry": null,
                    "pitExit": null,
                    "circuitId": null,
                    "revision": 3,
                    "updatedUtcMillis": 1700000000000
                }
            }
        """.trimIndent()
        val track = TrackJson.decode(old)
        assertEquals(TrackKind.Circuit, track.kind)
        assertFalse(track.pointToPoint)
        assertNull(track.finish)
        assertTrue(track.timeable)
        assertEquals(3, track.revision)
    }

    @Test
    fun `a point-to-point track survives the file round trip`() {
        val text = TrackJson.encode(hill)
        assertTrue("\"kind\": \"pointToPoint\"" in text, text)
        assertEquals(hill, TrackJson.decode(text))
        val circuitText = TrackJson.encode(hill.copy(kind = TrackKind.Circuit, finish = null))
        assertTrue("\"kind\": \"circuit\"" in circuitText)
    }

    @Test
    fun `point-to-point tracks are written as version 2 so older builds refuse them`() {
        val text = TrackJson.encode(hill)
        assertTrue("\"version\": 2" in text, text)
        // Circuits stay version 1, which builds from before FBBD-155 still read.
        val circuit = hill.copy(kind = TrackKind.Circuit, finish = null)
        val circuitText = TrackJson.encode(circuit)
        assertTrue("\"version\": 1" in circuitText, circuitText)
        // This build reads both, and a version 2 circuit too.
        assertEquals(hill, TrackJson.decode(text))
        assertEquals(circuit, TrackJson.decode(circuitText))
        assertEquals(circuit, TrackJson.decode(circuitText.replace("\"version\": 1", "\"version\": 2")))
        assertFailsWith<TrackFormatException> { TrackJson.decode(text.replace("\"version\": 2", "\"version\": 3")) }
    }

    @Test
    fun `a sprint shorter than the circuit minimum lap still counts`() {
        // Start at 100 m, finish at 600 m, driven in about 5 s.
        val sprint = hill.copy(sectorLines = emptyList(), finish = lineAt(sectorX))
        val d = drive(listOf(Leg(Xy(700.0, 0.0), 4.0), Leg(Xy(700.0, 8.0), 0.2), Leg(Xy(0.0, 8.0), 1.0)))
        val truth = d.passes.getValue(sectorX)[0] - d.passes.getValue(startX)[0]
        assertTrue(truth < DefaultMinLapSeconds * 1e6, "sprint takes ${truth / 1e6} s")
        val live = assertNotNull(timeSession(sprint, d.fixes.asSequence()).laps.singleOrNull())
        assertEquals(truth, live.timeMicros.toDouble(), 2_000.0)
        val replayed = replayLaps("sprint", session(d.fixes), sprint).laps.single()
        assertEquals(truth, replayed.lap.timeMicros.toDouble(), 2_000.0)
        assertTrue(timeSession(sprint, d.fixes.asSequence(), DefaultMinLapSeconds).laps.isEmpty())
    }

    @Test
    fun `the editor lists the finish line instead of pit lines`() {
        assertEquals(listOf(LineRef.StartFinish, LineRef.Sector(0), LineRef.Finish), hill.lineRefs())
        val circuit = hill.copy(kind = TrackKind.Circuit)
        assertEquals(
            listOf(LineRef.StartFinish, LineRef.Sector(0), LineRef.PitEntry, LineRef.PitExit),
            circuit.lineRefs(),
        )
        val moved = lineAt(1_050.0)
        assertEquals(moved, hill.withLine(LineRef.Finish, moved).finish)
        assertNull(hill.withLine(LineRef.Finish, null).finish)
    }
}
