@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapBook
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FBBD-58 and FBBD-59 on hand-made drives. Every line runs north to south, so it is crossed driving
 * east; between passes the car jumps back west or well clear of the lines, which crosses nothing forwards.
 */
class LapTimerTest {
    private val origin = GeoPoint(52.0, 21.0)
    private val frame = LocalFrame(origin)

    private fun line(x: Double, yFrom: Double, yTo: Double) =
        TrackLine(frame.toGeo(Xy(x, yTo)), frame.toGeo(Xy(x, yFrom)))

    private class Driver(val frame: LocalFrame, val timer: LapTimer) {
        val laps = mutableListOf<Lap>()
        val events = mutableListOf<LapEvent>()

        fun at(x: Double, y: Double, seconds: Double) {
            val g = frame.toGeo(Xy(x, y))
            val got = timer.update(Fix((seconds * 1e6).roundToLong(), g.lat, g.lon))
            events += got
            got.filterIsInstance<LapEvent.LapDone>().forEach { laps += it.lap }
        }

        /** Crosses the line at [x] exactly at [seconds], at 50 m/s, coming from well outside every line. */
        fun pass(x: Double, seconds: Double, y: Double = 0.0) {
            at(x - 1, y + 100, seconds - 0.04)
            at(x - 1, y, seconds - 0.02)
            at(x + 1, y, seconds + 0.02)
        }
    }

    @Test
    fun `out, timed, in and timed laps with a pit lane that bypasses the line`() {
        val track = Track(
            id = "t", name = "T", center = origin,
            startFinish = line(0.0, -15.0, 15.0),
            pitEntry = line(-100.0, 20.0, 40.0),
            pitExit = line(100.0, 20.0, 40.0),
        )
        val d = Driver(frame, LapTimer(track))
        d.at(-50.0, 0.0, 0.0)
        d.pass(0.0, 5.0)
        d.pass(0.0, 65.0)
        d.pass(0.0, 125.0)
        d.pass(-100.0, 180.0, y = 30.0)
        d.pass(100.0, 200.0, y = 30.0)
        d.pass(0.0, 245.0)
        d.pass(0.0, 305.0)
        assertEquals(
            listOf(LapKind.Out, LapKind.Timed, LapKind.Timed, LapKind.In, LapKind.Timed),
            d.laps.map { it.kind },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), d.laps.map { it.number })
        assertEquals(listOf(5_000L, 60_000L, 60_000L, 120_000L, 60_000L), d.laps.map { it.timeMillis })
    }

    @Test
    fun `the lap after an in lap is an out lap when the pit lane crosses the line`() {
        val track = Track(
            id = "t", name = "T", center = origin,
            startFinish = line(0.0, -15.0, 40.0),
            pitEntry = line(-100.0, 20.0, 40.0),
        )
        val d = Driver(frame, LapTimer(track))
        d.at(-50.0, 0.0, 0.0)
        d.pass(0.0, 5.0)
        d.pass(0.0, 65.0)
        d.pass(-100.0, 180.0, y = 30.0)
        d.pass(0.0, 190.0, y = 30.0)
        d.pass(0.0, 250.0)
        d.pass(0.0, 310.0)
        assertEquals(listOf(LapKind.Out, LapKind.Timed, LapKind.In, LapKind.Out, LapKind.Timed), d.laps.map { it.kind })
    }

    @Test
    fun `a second pass within the minimum lap time is ignored`() {
        val track = Track(id = "t", name = "T", center = origin, startFinish = line(0.0, -15.0, 15.0))
        val d = Driver(frame, LapTimer(track))
        d.at(-50.0, 0.0, 0.0)
        d.pass(0.0, 5.0)
        d.pass(0.0, 7.0)
        d.pass(0.0, 65.0)
        assertEquals(listOf(5_000L, 60_000L), d.laps.map { it.timeMillis })
    }

    @Test
    fun `sector times, bests and gains match a hand calculation`() {
        val track = Track(
            id = "t", name = "T", center = origin,
            startFinish = line(0.0, -15.0, 15.0),
            sectorLines = listOf(line(200.0, -15.0, 15.0), line(400.0, -15.0, 15.0)),
        )
        val d = Driver(frame, LapTimer(track))
        val book = LapBook(track.sectorCount)
        d.at(-50.0, 0.0, 0.0)
        var t = 10.0
        d.pass(0.0, t)
        // Laps A, B and C: sector times in seconds.
        for (sectors in listOf(listOf(20.0, 30.0, 25.0), listOf(19.5, 31.0, 25.5), listOf(20.2, 29.8, 24.6))) {
            t += sectors[0]; d.pass(200.0, t)
            t += sectors[1]; d.pass(400.0, t)
            t += sectors[2]; d.pass(0.0, t)
        }
        d.laps.forEach { book.add(it) }
        val out = d.laps[0]
        val a = d.laps[1]
        val b = d.laps[2]
        val c = d.laps[3]
        assertEquals(LapKind.Out, out.kind)
        assertEquals(listOf(null, null, null), out.sectorMicros)
        assertEquals(listOf(20_000_000L, 30_000_000L, 25_000_000L), a.sectorMicros)
        assertEquals(c, book.best)
        assertEquals(74_600L, book.best?.timeMillis)
        assertEquals(listOf(19_500_000L, 29_800_000L, 24_600_000L), book.bestSectors)
        assertEquals(73_900_000L, book.theoreticalBestMicros)
        assertEquals(listOf(-700_000L, 1_200_000L, 900_000L), book.gainVsBestLap(b))
        assertEquals(listOf(500_000L, 200_000L, 400_000L), book.gainVsBestSectors(a))
    }

    @Test
    fun `a missed sector line leaves only its sectors untimed`() {
        val track = Track(
            id = "t", name = "T", center = origin,
            startFinish = line(0.0, -15.0, 15.0),
            sectorLines = listOf(line(200.0, -15.0, 15.0), line(400.0, -15.0, 15.0)),
        )
        val d = Driver(frame, LapTimer(track))
        d.at(-50.0, 0.0, 0.0)
        d.pass(0.0, 10.0)
        // GNSS gap over the first sector line.
        d.pass(400.0, 60.0)
        d.pass(0.0, 85.0)
        val lap = d.laps.last()
        assertEquals(LapKind.Timed, lap.kind)
        assertEquals(75_000L, lap.timeMillis)
        assertNull(lap.sectorMicros[0])
        assertNull(lap.sectorMicros[1])
        assertEquals(25_000_000L, lap.sectorMicros[2])
    }

    @Test
    fun `out of order sector lines are ignored`() {
        val track = Track(
            id = "t", name = "T", center = origin,
            startFinish = line(0.0, -15.0, 15.0),
            sectorLines = listOf(line(200.0, -15.0, 15.0), line(400.0, -15.0, 15.0)),
        )
        val d = Driver(frame, LapTimer(track))
        d.at(-50.0, 0.0, 0.0)
        d.pass(0.0, 10.0)
        d.pass(200.0, 30.0)
        d.pass(400.0, 60.0)
        d.pass(200.0, 70.0)
        d.pass(0.0, 85.0)
        assertEquals(listOf(20_000_000L, 30_000_000L, 25_000_000L), d.laps.last().sectorMicros)
    }

    @Test
    fun `laps on the simulated circuit match the true times`() {
        val circuit = FakeCircuit()
        val paces = listOf(1.0, 1.04, 0.95, 1.01, 0.99)
        val drive = circuit.drive(paces)
        val timer = LapTimer(circuit.track())
        val laps = drive.fixes.flatMap { timer.update(it) }.filterIsInstance<LapEvent.LapDone>().map { it.lap }
        val passes = drive.lineTimesMicros.getValue(0.0)
        assertEquals(paces.size + 1, laps.size)
        assertEquals(LapKind.Out, laps.first().kind)
        laps.drop(1).forEachIndexed { i, lap ->
            assertEquals(LapKind.Timed, lap.kind)
            val truth = passes[i + 1] - passes[i]
            assertEquals(truth, lap.timeMicros.toDouble(), 5_000.0, "lap ${lap.number}")
        }
    }
}
