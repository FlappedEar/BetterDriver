@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.GForce
import com.flappedear.betterdriver.timing.overlay.LapPhase
import com.flappedear.betterdriver.timing.overlay.MapFit
import com.flappedear.betterdriver.timing.overlay.MeasuredG
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayLayout
import com.flappedear.betterdriver.timing.overlay.TimeSeries
import com.flappedear.betterdriver.timing.overlay.gDot
import com.flappedear.betterdriver.timing.overlay.gnssG
import com.flappedear.betterdriver.timing.overlay.thin
import com.flappedear.betterdriver.timing.overlay.totalMicros
import com.flappedear.betterdriver.timing.overlay.videoCuts
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-152: what the video export's data overlay draws, and where, at each frame. */
class OverlayTest {
    private val circuit = FakeCircuit()
    private val track = circuit.track()

    private fun session(drive: FakeCircuit.Drive, extra: (Long) -> List<ChannelValue> = { emptyList() }) =
        drive.fixes.map {
            SampleSet(
                it.tMicros,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, it.lat),
                    ChannelValue(GnssChannels.LON.id, it.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, 120.0),
                ) + extra(it.tMicros),
            )
        }

    @Test
    fun `a series interpolates between samples and has nothing outside them or across a gap`() {
        val s = TimeSeries.of(listOf(0L to 0.0, 1_000_000L to 10.0, 5_000_000L to 50.0, 1_000_000L to 99.0))
        assertEquals(5.0, s.at(500_000))
        assertEquals(10.0, s.at(1_000_000))
        assertNull(s.at(-1))
        assertNull(s.at(5_000_001))
        assertNull(s.at(3_000_000), "4 s between samples is a lost fix, not a value")
        assertEquals(3, s.size)
    }

    @Test
    fun `smoothing averages over the window`() {
        val s = TimeSeries.of((0..10).map { it * 100_000L to if (it == 5) 10.0 else 0.0 }).smoothed(100_000)
        assertEquals(10.0 / 3, assertNotNull(s.at(500_000)), 1e-9)
        assertEquals(0.0, assertNotNull(s.at(0)), 1e-9)
    }

    @Test
    fun `G from GNSS gives the pull of a steady circle to the left and of braking on a straight`() {
        // 30 m/s on a 100 m radius, anticlockwise (a left turn): 0.92 g to the left.
        val v = 30.0
        val r = 100.0
        val times = LongArray(250) { it * 40_000L }
        val circle = times.map { t ->
            val a = v * t / 1e6 / r
            Xy(r * cos(a), r * sin(a))
        }
        val g = gnssG(times, circle, DoubleArray(times.size) { v })
        val mid = assertNotNull(g[125])
        assertEquals(-v * v / r / 9.80665, mid.lateral, 0.01)
        assertEquals(0.0, mid.longitudinal, 1e-9)

        // Braking at 8 m/s² from 40 m/s, straight north, with speed taken from the path.
        val braking = times.map { t -> val s = t / 1e6; Xy(0.0, 40 * s - 4 * s * s) }
        val b = assertNotNull(gnssG(times, braking, null)[60])
        assertEquals(-8 / 9.80665, b.longitudinal, 0.02)
        assertEquals(0.0, b.lateral, 1e-6)
    }

    @Test
    fun `G from GNSS at 1 Hz still uses the neighbouring fixes`() {
        val times = LongArray(5) { it * 1_000_000L }
        val path = times.map { Xy(0.0, it / 1e6 * 20) }
        assertNotNull(gnssG(times, path, null)[2])
    }

    @Test
    fun `lap, lap time and delta to the best lap follow the session clock`() {
        val drive = circuit.drive(listOf(1.0, 1.05, 0.95))
        val sets = session(drive)
        val laps = replayLaps("s", sets, track)
        val data = OverlayData(sets, laps)
        val best = assertNotNull(laps.best)
        val slow = laps.laps.single { it.lap.number == 4 }
        assertEquals(3, best.lap.number)

        val inBest = data.at(best.lap.startMicros + 10_000_000)
        assertEquals(3, inBest.lap?.number)
        assertEquals(10_000_000L, inBest.lap?.lapMicros)
        assertEquals(0L, inBest.lap?.deltaMicros)
        assertTrue(inBest.lap?.isBest == true)

        // The slower lap loses time all the way round, and at its end the delta is the lap time difference.
        val early = assertNotNull(data.at(slow.lap.startMicros + 5_000_000).lap?.deltaMicros)
        val late = assertNotNull(data.at(slow.lap.endMicros - 1).lap?.deltaMicros)
        assertTrue(early > 0 && late > early, "early $early, late $late")
        assertEquals((slow.lap.timeMicros - best.lap.timeMicros).toDouble(), late.toDouble(), 30_000.0)

        // The out lap has no delta; after the last line the lap still being driven has no kind.
        val out = assertNotNull(data.at(laps.laps.first().lap.startMicros + 1).lap)
        assertEquals(LapKind.Out, out.kind)
        assertNull(out.deltaMicros)
        val after = assertNotNull(data.at(slow.lap.endMicros + 2_000_000).lap)
        assertEquals(5, after.number)
        assertNull(after.kind)
        assertEquals(2_000_000L, after.lapMicros)
    }

    @Test
    fun `a single-lap export shows its lap only - ready before the line, held after it`() {
        val drive = circuit.drive(listOf(1.0, 1.05, 0.95))
        val sets = session(drive)
        val laps = replayLaps("s", sets, track)
        val best = assertNotNull(laps.best)
        val slow = laps.laps.single { it.lap.number == 4 }
        val data = OverlayData(sets, laps, hotlap = 4)

        // Before its start line the previous lap is being driven, but the export shows lap 4 at 0:00.
        val before = assertNotNull(data.at(slow.lap.startMicros - 3_000_000).lap)
        assertEquals(4, before.number)
        assertEquals(LapPhase.Ready, before.phase)
        assertEquals(0L, before.lapMicros)
        assertNull(before.deltaMicros)

        val during = assertNotNull(data.at(slow.lap.startMicros + 10_000_000).lap)
        assertEquals(LapPhase.Running, during.phase)
        assertEquals(10_000_000L, during.lapMicros)

        // After its line: its final time and the gap to the best lap, held.
        for (after in listOf(slow.lap.endMicros, slow.lap.endMicros + 5_000_000)) {
            val held = assertNotNull(data.at(after).lap)
            assertEquals(4, held.number)
            assertEquals(LapPhase.Finished, held.phase)
            assertEquals(slow.lap.timeMicros, held.lapMicros)
            assertEquals(slow.lap.timeMicros - best.lap.timeMicros, held.deltaMicros)
        }

        // Without a hotlap, or with a lap the session does not have, laps follow the clock as before.
        for (plain in listOf(OverlayData(sets, laps), OverlayData(sets, laps, hotlap = 99))) {
            val f = assertNotNull(plain.at(slow.lap.startMicros - 3_000_000).lap)
            assertEquals(3, f.number)
            assertEquals(LapPhase.Running, f.phase)
        }
    }

    @Test
    fun `speed and position come from the receiver, the outline from the best lap`() {
        val drive = circuit.drive(listOf(1.0, 1.0))
        val sets = session(drive)
        val laps = replayLaps("s", sets, track)
        val data = OverlayData(sets, laps)
        val t = drive.fixes[100].tMicros + 20_000
        val f = data.at(t)
        assertEquals(120.0, f.speedKmh)
        val p = assertNotNull(f.position)
        assertTrue(data.outline.minOf { (it - p).length() } < 5.0, "the car is on the map's track")
        assertEquals(assertNotNull(laps.best).points.size, data.outline.size)
    }

    @Test
    fun `without a track there is no lap, but speed, map and G still show`() {
        val drive = circuit.drive(listOf(1.0))
        val data = OverlayData(session(drive), laps = null)
        val f = data.at(drive.fixes[200].tMicros)
        assertNull(f.lap)
        assertNotNull(f.position)
        assertNotNull(f.g)
        assertEquals(drive.fixes.size, data.outline.size)
    }

    /** FBBD-226: the overlay draws the accelerometers' G as the analysis corrects them, else GNSS's. */
    @Test
    fun `measured G is drawn where it is given, the GNSS G elsewhere`() {
        val drive = circuit.drive(listOf(1.0))
        val sets = session(drive)
        val plain = OverlayData(sets, null)
        val gnss = drive.fixes.associate { it.tMicros to plain.at(it.tMicros).g }
        // A steady 0.4 g to the right, sampled at 25 Hz: smoothing leaves it as it is.
        val right = (0 until (drive.fixes.last().tMicros / 40_000L).toInt()).map { it * 40_000L to 0.4 }
        val data = OverlayData(sets, null, MeasuredG(forward = null, right = right))
        val checked = drive.fixes.filter { gnss[it.tMicros] != null }
        assertTrue(checked.size > 100)
        for (f in checked.drop(10).dropLast(10).filterIndexed { i, _ -> i % 25 == 0 }) {
            val expected = assertNotNull(gnss[f.tMicros])
            val g = assertNotNull(data.at(f.tMicros).g)
            assertEquals(0.4, g.lateral, 1e-9, "at ${f.tMicros}")
            assertEquals(expected.longitudinal, g.longitudinal, 1e-9, "at ${f.tMicros}")
        }
    }

    @Test
    fun `a lap inside one clip is one cut, trimmed to the lap`() {
        val clips = listOf(VideoClip("phone", "a.mp4", 1_000_000, 601_000_000))
        val cuts = videoCuts(clips, { 600_000_000L }, 100_000_000, 190_000_000)
        val c = cuts.single()
        assertEquals(99_000_000L, c.fromMicros)
        assertEquals(189_000_000L, c.toMicros)
        assertEquals(0L, c.outputStartMicros)
        assertEquals(100_000_000L, c.sessionMicrosAt(0))
        assertEquals(130_000_000L, c.sessionMicrosAt(30_000_000))
    }

    @Test
    fun `a lap across two clips is two cuts back to back, and a gap between them is skipped`() {
        val clips = listOf(
            VideoClip("phone", "b.mp4", 62_000_000, null),
            VideoClip("phone", "a.mp4", 0, null),
        )
        val durations = mapOf("a.mp4" to 60_000_000L, "b.mp4" to 60_000_000L)
        val cuts = videoCuts(clips, { durations[it.file] }, 50_000_000, 80_000_000)
        assertEquals(listOf("a.mp4", "b.mp4"), cuts.map { it.clip.file })
        assertEquals(50_000_000L, cuts[0].fromMicros)
        assertEquals(60_000_000L, cuts[0].toMicros)
        assertEquals(0L, cuts[1].fromMicros)
        assertEquals(18_000_000L, cuts[1].toMicros)
        assertEquals(10_000_000L, cuts[1].outputStartMicros)
        assertEquals(28_000_000L, cuts.totalMicros())
        // The second cut's first frame is session time 62 s, after the 2 s the cameras missed.
        assertEquals(62_000_000L, cuts[1].sessionMicrosAt(10_000_000))
    }

    @Test
    fun `the whole video takes every clip once, even where they overlap`() {
        val clips = listOf(
            VideoClip("phone", "a.mp4", -2_000_000, 30_000_000),
            VideoClip("phone", "b.mp4", 29_000_000, null),
            VideoClip("phone", "c.mp4", 100_000_000, null),
        )
        val durations = mapOf("a.mp4" to 33_000_000L, "b.mp4" to 31_000_000L)
        val cuts = videoCuts(clips, { durations[it.file] }, Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(listOf("a.mp4", "b.mp4"), cuts.map { it.clip.file }, "c has no known length")
        assertEquals(0L, cuts[0].fromMicros)
        assertEquals(33_000_000L, cuts[0].toMicros, "the file's own length wins over the logged end")
        // b starts where a ends, 2 s into its file.
        assertEquals(2_000_000L, cuts[1].fromMicros)
        assertEquals(31_000_000L, cuts[1].toMicros)
        assertEquals(33_000_000L, cuts[1].outputStartMicros)
        assertEquals(31_000_000L, cuts[1].sessionMicrosAt(33_000_000))
    }

    @Test
    fun `the band fits the picture and scales with it`() {
        val hd = OverlayLayout.of(1920, 1080)
        val uhd = OverlayLayout.of(3840, 2160)
        assertEquals(216, hd.height)
        assertEquals(hd.height * 2, uhd.height)
        assertEquals(hd.numberSize * 2, uhd.numberSize, 1e-3f)
        for (size in listOf(1920 to 1080, 3840 to 2160, 1080 to 1920, 640 to 480, 1280 to 720)) {
            val l = OverlayLayout.of(size.first, size.second)
            val boxes = listOf(l.speed, l.lap, l.delta, l.gMeter, l.map)
            for (b in boxes) {
                assertTrue(b.left >= 0 && b.right <= l.width && b.top >= 0 && b.bottom <= l.height, "$size $b")
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse(boxes[i].overlaps(boxes[j]), "$size: ${boxes[i]} and ${boxes[j]}")
            }
            assertEquals(l.gMeter.width, l.gMeter.height, 1e-3f)
            assertTrue(l.height <= size.second / 4)
        }
        assertTrue(OverlayLayout.of(1080, 1920).height < hd.height, "a portrait picture has a smaller band")
    }

    @Test
    fun `the map keeps the track's shape, north up, inside its box`() {
        val box = Box(100f, 0f, 300f, 100f)
        val fit = MapFit(listOf(Xy(0.0, 0.0), Xy(1000.0, 0.0), Xy(1000.0, 500.0), Xy(0.0, 500.0)), box, 10f)
        val sw = fit.project(Xy(0.0, 0.0))
        val ne = fit.project(Xy(1000.0, 500.0))
        assertEquals(120f, sw.x, 1e-3f)
        assertEquals(90f, sw.y, 1e-3f) // south is down
        assertEquals(280f, ne.x, 1e-3f) // 160 px wide for 80 px high: the shape is kept and centred
        assertEquals(10f, ne.y, 1e-3f)
        val far = fit.project(Xy(5000.0, -5000.0))
        assertEquals(290f, far.x, 1e-3f)
        assertEquals(90f, far.y, 1e-3f)
        assertEquals(200f, MapFit(emptyList(), box, 10f).project(Xy(3.0, 4.0)).x, 1e-3f)
    }

    @Test
    fun `the G dot moves up when speeding up, right in a right turn, and stays on the ring`() {
        val box = Box(0f, 0f, 100f, 100f)
        assertEquals(50f, gDot(GForce(0.0, 0.0), box).x)
        val accelerating = gDot(GForce(0.75, 0.0), box)
        assertEquals(25f, accelerating.y, 1e-3f)
        val right = gDot(GForce(0.0, 1.5), box)
        assertEquals(100f, right.x, 1e-3f)
        val hard = gDot(GForce(-3.0, 3.0), box)
        assertEquals(50.0, Math.hypot(hard.x - 50.0, hard.y - 50.0), 1e-3)
        assertTrue(hard.x > 50 && hard.y > 50, "braking in a right turn: down and right")
    }

    @Test
    fun `thinning keeps the ends and at most the asked number of points`() {
        val t = thin((0 until 1000).toList(), 10)
        assertEquals(10, t.size)
        assertEquals(0, t.first())
        assertEquals(999, t.last())
        assertEquals(listOf(1, 2), thin(listOf(1, 2), 10))
    }
}
