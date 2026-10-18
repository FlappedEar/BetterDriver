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
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `without a track the whole session is replayed`() {
        val replay = assertNotNull(SessionReplay.of(recording(2), track = null, start))
        val first = replay.fix(0).toSet()
        assertTrue(first.any { it.channelId == GnssChannels.LAT.id })
        assertEquals(start, replay.fix(0).single { it.channelId == GnssChannels.UTC_MS.id }.value.toLong())
    }

    @Test
    fun `a session without two positions is not replayed`() {
        val position = listOf(ChannelValue(GnssChannels.LAT.id, 52.0), ChannelValue(GnssChannels.LON.id, 21.0))
        val one = listOf(SampleSet(0, position))
        assertNull(SessionReplay.of(one, track, start))
        assertNull(SessionReplay.of(emptyList(), track, start))
    }
}
