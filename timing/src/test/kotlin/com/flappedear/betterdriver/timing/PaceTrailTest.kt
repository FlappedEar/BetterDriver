@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.Pace
import com.flappedear.betterdriver.timing.overlay.TrailPoint
import com.flappedear.betterdriver.timing.overlay.paceRuns
import com.flappedear.betterdriver.timing.overlay.trail
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-451: the video map's trail coloured by the live delta to the best lap before it (FBBD-449). */
class PaceTrailTest {
    private val circuit = FakeCircuit()
    private val track = circuit.track()

    // Lap 2 (first timed) at pace 1.0, lap 3 faster (the session's best), lap 4 slower than lap 3.
    private val drive = circuit.drive(listOf(1.0, 1.05, 0.95))
    private val sets = drive.fixes.map {
        SampleSet(
            it.tMicros,
            listOf(
                ChannelValue(GnssChannels.LAT.id, it.lat),
                ChannelValue(GnssChannels.LON.id, it.lon),
                ChannelValue(GnssChannels.SPEED_KMH.id, 120.0),
            ),
        )
    }
    private val laps = replayLaps("s", sets, track)
    private fun lap(n: Int) = laps.laps.single { it.lap.number == n }.lap

    @Test
    fun `the pace follows Arek's threshold - green from -0,001 s down, orange above`() {
        assertNull(Pace.of(null))
        assertEquals(Pace.Ahead, Pace.of(-1_000))
        assertEquals(Pace.Ahead, Pace.of(-250_000))
        assertEquals(Pace.Behind, Pace.of(-999))
        assertEquals(Pace.Behind, Pace.of(0))
        assertEquals(Pace.Behind, Pace.of(1))
        assertEquals(Pace.Behind, Pace.of(300_000))
    }

    @Test
    fun `a lap faster than the best before it is green, a slower one orange`() {
        val data = OverlayData(sets, laps)
        // Lap 3 gains on lap 2 all the way round: once clear of the line, every point is ahead.
        val faster = data.paceTrail(lap(3).endMicros - 1).filterNotNull()
        assertTrue(faster.size > 100, "points ${faster.size}")
        val clear = faster.drop(faster.size / 10)
        assertTrue(clear.all { it.pace == Pace.Ahead }, clear.map { it.pace }.distinct().toString())
        // Each point's pace is the delta the lap tile showed at that moment.
        val mid = lap(3).startMicros + (lap(3).endMicros - lap(3).startMicros) / 2
        assertEquals(Pace.of(data.at(mid).lap?.deltaMicros), data.paceTrail(mid).filterNotNull().last().pace)

        // Lap 4 loses to lap 3, the best before it.
        val slower = data.paceTrail(lap(4).endMicros - 1).filterNotNull().drop(10)
        assertTrue(slower.isNotEmpty() && slower.all { it.pace == Pace.Behind })

        // The trail is the same line as before, only coloured.
        val t = lap(4).startMicros + 20_000_000
        assertEquals(data.trail(t), data.paceTrail(t).map { it?.xy })
    }

    @Test
    fun `without a lap before it to compare with the trail keeps its single colour`() {
        val data = OverlayData(sets, laps)
        // The first timed lap and the drive after the last line have no reference.
        val times = listOf(lap(2).startMicros + 10_000_000, lap(2).endMicros - 1, lap(4).endMicros + 3_000_000)
        for (t in times) {
            val trail = data.paceTrail(t).filterNotNull()
            assertTrue(trail.isNotEmpty())
            assertTrue(trail.all { it.pace == null }, "at $t")
            assertTrue(paceRuns(data.paceTrail(t)).all { it.pace == null })
        }
    }

    @Test
    fun `a single-lap export colours its lap as the full video does`() {
        val alone = OverlayData(sets, laps, hotlap = 3)
        val t = lap(3).startMicros + 30_000_000
        assertEquals(OverlayData(sets, laps).paceTrail(t), alone.paceTrail(t))
        assertEquals(Pace.Ahead, alone.paceTrail(t).filterNotNull().last().pace)
    }

    @Test
    fun `runs split where the pace changes and stay joined, and break at a gap`() {
        fun p(x: Double, pace: Pace?) = TrailPoint(Xy(x, 0.0), pace)
        val runs = paceRuns(
            listOf(
                p(0.0, Pace.Behind), p(1.0, Pace.Behind), p(2.0, Pace.Ahead), p(3.0, Pace.Ahead),
                null,
                p(10.0, Pace.Ahead), // A lone point draws nothing.
                null,
                p(20.0, null), p(21.0, null),
            ),
        )
        assertEquals(listOf(Pace.Behind, Pace.Ahead, null), runs.map { it.pace })
        assertEquals(listOf(0.0, 1.0), runs[0].points.map { it.x })
        // The green run starts where the orange one ends.
        assertEquals(listOf(1.0, 2.0, 3.0), runs[1].points.map { it.x })
        assertEquals(listOf(20.0, 21.0), runs[2].points.map { it.x })
        assertTrue(paceRuns(emptyList()).isEmpty())
    }
}
