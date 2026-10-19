@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Demo track receiver replaying a recorded session (FBBD-235). The "recording" here is the made-up
 * demo circuit at 10 Hz, as an imported VBO might be: no real positions in the repo.
 */
class SessionReplayTest {
    private val track = DemoCircuit.track
    private val start = 1_790_000_000_000L

    /** [minutes] of the demo circuit recorded at [hz], as the session's sample sets. */
    private fun recording(minutes: Int, hz: Int = 10, speed: Boolean = true): List<SampleSet> {
        val source = DemoCircuit(start, rateHz = hz)
        return (0L until minutes * 60L * hz).map { n ->
            val values = source.fix(n).filter { speed || it.channelId != GnssChannels.SPEED_KMH.id }
            SampleSet(n * 1_000_000L / hz, values)
        }
    }

    private fun replayed(replay: SessionReplay, minutes: Int) =
        (0L until minutes * 60L * replay.rateHz).map { n -> SampleSet(n * 1_000_000L / replay.rateHz, replay.fix(n)) }

    @Test
    fun `its timed laps are lapped again and again, at the recording's lap times`() {
        val source = recording(10)
        val recorded = timeSession(track, source.mapNotNull { it.toFix() }.asSequence()).laps
            .filter { it.kind == LapKind.Timed }
        val replay = assertNotNull(SessionReplay.of(source, track, start))
        val laps = timeSession(track, replayed(replay, 25).mapNotNull { it.toFix() }.asSequence()).laps
            .filter { it.kind == LapKind.Timed }
        assertTrue(laps.size > recorded.size, "${laps.size} laps replayed from ${recorded.size}")
        val best = recorded.minOf { it.timeMillis }
        assertTrue(laps.all { it.timeMillis >= best - 50 }, "replayed ${laps.map { it.timeMillis }}, best $best")
        assertTrue(laps.all { it.allSectors })
    }

    @Test
    fun `fixes come at 25 Hz and the loop joins without a jump`() {
        val replay = assertNotNull(SessionReplay.of(recording(10), track, start))
        assertEquals(25, replay.rateHz)
        val fixes = replayed(replay, 20).mapNotNull { it.toFix() }
        val steps = fixes.zipWithNext { a, b -> distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }
        assertTrue(steps.max() < 3.0, "longest step ${steps.max()} m")
    }

    @Test
    fun `speed comes from the path when the recording has none`() {
        val replay = assertNotNull(SessionReplay.of(recording(5, speed = false), track, start))
        val kmh = replayed(replay, 3).map { s -> s.values.single { it.channelId == GnssChannels.SPEED_KMH.id }.value }
        assertTrue(kmh.max() in 140.0..210.0, "top ${kmh.max()} km/h")
    }

    @Test
    fun `fixes carry positions and the session's start time`() {
        val replay = assertNotNull(SessionReplay.of(recording(5), track, start))
        assertTrue(replay.fix(0).any { it.channelId == GnssChannels.LAT.id })
        assertEquals(start, replay.fix(0).single { it.channelId == GnssChannels.UTC_MS.id }.value.toLong())
    }

    @Test
    fun `a replay written as text reads back the same fixes`() {
        val replay = assertNotNull(SessionReplay.of(recording(5), track, start))
        val back = SessionReplay.read(replay.write(), start)
        for (n in listOf(0L, 7L, 1_000L, 9_999L)) {
            val a = replay.fix(n).associate { it.channelId to it.value }
            val b = back.fix(n).associate { it.channelId to it.value }
            assertEquals(a.keys, b.keys)
            a.forEach { (id, v) ->
                val within = if (id == GnssChannels.LAT.id || id == GnssChannels.LON.id) 1e-6 else 0.05
                assertEquals(v, b.getValue(id), within, "channel $id at $n")
            }
        }
    }

    @Test
    fun `text that is not a replay is refused`() {
        assertFailsWith<IllegalArgumentException> { SessionReplay.read("hello", start) }
        val onePoint = SessionReplay.FormatLine + "\n1 2 3 - -"
        assertFailsWith<IllegalArgumentException> { SessionReplay.read(onePoint, start) }
    }

    @Test
    fun `a session without two timed laps is not replayed`() {
        // It would jump from where it ended back to where it began on every loop.
        assertNull(SessionReplay.of(recording(5), track = null, start))
        assertNull(SessionReplay.of(recording(1), track, start))
    }

    @Test
    fun `a stop between stints is not replayed`() {
        val stint = recording(5)
        val parked = stint.last().values
        val stop = (1L..1_800L).map { n -> SampleSet(stint.last().tMicros + n * 100_000L, parked) }
        val after = recording(5).map { SampleSet(stop.last().tMicros + 100_000L + it.tMicros, it.values) }
        val replay = assertNotNull(SessionReplay.of(stint + stop + after, track, start))
        val fixes = replayed(replay, 20).mapNotNull { it.toFix() }
        val steps = fixes.zipWithNext { a, b -> distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }
        val longestStill = steps.fold(0 to 0) { (run, most), d ->
            if (d < 0.05) (run + 1).let { it to maxOf(most, it) } else 0 to most
        }.second
        assertTrue(longestStill < 25, "standing still for ${longestStill / 25.0} s")
        assertTrue(steps.max() < 3.0, "longest step ${steps.max()} m")
    }

    @Test
    fun `two timed laps, one with a stop in it, are not replayed`() {
        val drive = recording(4)
        val timed = timeSession(track, drive.mapNotNull { it.toFix() }.asSequence()).laps
            .filter { it.kind == LapKind.Timed }
        assertEquals(2, timed.size)
        // Three minutes parked halfway round the second timed lap.
        val half = (timed[1].startMicros + timed[1].endMicros) / 2
        val (before, after) = drive.partition { it.tMicros < half }
        val stop = (1L..1_800L).map { n -> SampleSet(before.last().tMicros + n * 100_000L, before.last().values) }
        val moved = after.map { SampleSet(it.tMicros + 180_000_000L, it.values) }
        assertNull(SessionReplay.of(before + stop + moved, track, start))
    }

    @Test
    fun `a lap in which the car stands still is not replayed, with or without recorded speed`() {
        for (speed in listOf(true, false)) {
            val drive = recording(6, speed = speed)
            val timed = timeSession(track, drive.mapNotNull { it.toFix() }.asSequence()).laps
                .filter { it.kind == LapKind.Timed }
            // Ten seconds parked halfway round the second timed lap: not slow enough to leave out by time.
            val half = (timed[1].startMicros + timed[1].endMicros) / 2
            val (before, after) = drive.partition { it.tMicros < half }
            val parked = before.last().values.map {
                if (it.channelId == GnssChannels.SPEED_KMH.id) ChannelValue(it.channelId, 0.0) else it
            }
            val stop = (1L..100L).map { n -> SampleSet(before.last().tMicros + n * 100_000L, parked) }
            val moved = after.map { SampleSet(it.tMicros + 10_000_000L, it.values) }
            val replay = assertNotNull(SessionReplay.of(before + stop + moved, track, start))
            val fixes = replayed(replay, 20).mapNotNull { it.toFix() }
            val steps = fixes.zipWithNext { a, b -> distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }
            assertTrue(steps.count { it < 0.05 } < 25, "standing still with speed=$speed")
        }
    }

    @Test
    fun `positions at 0, 0 are no fix and are not replayed`() {
        val noFix = listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))
        val source = recording(10)
        val gaps = source.mapIndexed { i, set ->
            if (i < 50 || i % 600 in 1..20) SampleSet(set.tMicros, noFix) else set
        }
        val replay = assertNotNull(SessionReplay.of(gaps, track, start))
        val fixes = replayed(replay, 20).mapNotNull { it.toFix() }
        assertTrue(fixes.all { distanceMeters(GeoPoint(it.lat, it.lon), track.center) < 2_000.0 })
    }

    @Test
    fun `a point-to-point track is not replayed`() {
        val run = track.copy(kind = TrackKind.PointToPoint, finish = track.sectorLines.first())
        assertNull(SessionReplay.of(recording(2), run, start))
    }

    @Test
    fun `a session without two positions is not replayed`() {
        val position = listOf(ChannelValue(GnssChannels.LAT.id, 52.0), ChannelValue(GnssChannels.LON.id, 21.0))
        val one = listOf(SampleSet(0, position))
        assertNull(SessionReplay.of(one, track, start))
        assertNull(SessionReplay.of(emptyList(), track, start))
    }
}
