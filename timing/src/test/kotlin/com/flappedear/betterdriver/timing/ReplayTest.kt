@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.firstPosition
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FBBD-91: laps of saved sessions, and two laps from different sessions matched by position. */
class ReplayTest {
    private val circuit = FakeCircuit()
    private val track = circuit.track()

    /** A session as the recorder stores it: one set per fix with position and a speed. */
    private fun session(drive: FakeCircuit.Drive): List<SampleSet> = drive.fixes.map {
        SampleSet(
            it.tMicros,
            listOf(
                ChannelValue(GnssChannels.LAT.id, it.lat),
                ChannelValue(GnssChannels.LON.id, it.lon),
                ChannelValue(GnssChannels.SPEED_KMH.id, 100.0),
            ),
        )
    }

    @Test
    fun `replays the laps the track screen timed, each with its path`() {
        val drive = circuit.drive(listOf(1.0, 1.05, 0.97))
        val laps = replayLaps("s1", session(drive), track)
        assertEquals(listOf(LapKind.Out, LapKind.Timed, LapKind.Timed, LapKind.Timed), laps.laps.map { it.lap.kind })
        val starts = drive.lineTimesMicros.getValue(0.0)
        for ((i, trace) in laps.laps.drop(1).withIndex()) {
            val trueTime = starts[i + 1] - starts[i]
            assertEquals(trueTime, trace.lap.timeMicros.toDouble(), 2_000.0, "lap ${trace.lap.number}")
            assertEquals(0L, trace.points.first().lapMicros)
            assertEquals(trace.lap.timeMicros, trace.points.last().lapMicros)
            assertEquals(circuit.length, trace.lengthMeters, circuit.length * 0.005)
            assertEquals(100.0, assertNotNull(trace.at(trace.lap.timeMicros / 2).speedKmh), 1e-9)
        }
        assertEquals(3, assertNotNull(laps.best).lap.number)
    }

    @Test
    fun `aligns laps of two sessions by position`() {
        val a = circuit.drive(listOf(1.0, 1.0), startMicros = 5_000_000L)
        val b = circuit.drive(listOf(1.0, 0.95), rateHz = 10, startMicros = 77_000_000L)
        // laps[0] is the out lap; laps[2] is driven at the second pace.
        val lapA = replayLaps("a", session(a), track).laps[2]
        val lapB = replayLaps("b", session(b), track).laps[2]
        val align = LapAlignment(lapA, lapB)

        assertEquals(lapB.lap.timeMicros - lapA.lap.timeMicros, align.deltaMicrosAt(lapA.lap.timeMicros))
        assertEquals(0L, align.deltaMicrosAt(0))
        assertTrue(align.deltaMicrosAt(lapA.lap.timeMicros) > 0, "B is the slower lap")

        // The true times both laps passed a third and two thirds of the way round.
        for (mark in listOf(1.0 / 3, 2.0 / 3)) {
            val tA = a.lineTimesMicros.getValue(mark)[1] - a.lineTimesMicros.getValue(0.0)[1]
            val tB = b.lineTimesMicros.getValue(mark)[1] - b.lineTimesMicros.getValue(0.0)[1]
            assertEquals(tB, align.otherMicrosAt(tA.roundToLong()).toDouble(), 20_000.0, "B at $mark")
            assertEquals(tA, align.referenceMicrosAt(tB.roundToLong()).toDouble(), 20_000.0, "A at $mark")
        }
    }

    @Test
    fun `delta by distance runs the whole reference lap and only grows for a steadily slower lap`() {
        val a = circuit.drive(listOf(1.0, 1.0))
        val b = circuit.drive(listOf(1.0, 0.9))
        val align = LapAlignment(replayLaps("a", session(a), track).laps[2], replayLaps("b", session(b), track).laps[2])
        val deltas = align.deltaByDistance(10.0)
        assertEquals(0.0, deltas.first().distanceMeters)
        assertEquals(align.reference.lengthMeters, deltas.last().distanceMeters)
        // GNSS steps at 25 Hz allow a few ms of noise between points.
        assertTrue(deltas.zipWithNext().all { (p, q) -> q.deltaMicros >= p.deltaMicros - 5_000 })
        assertTrue(abs(deltas.last().deltaMicros - (align.other.lap.timeMicros - align.reference.lap.timeMicros)) < 1)
    }

    @Test
    fun `no laps away from the track, and the first position skips a receiver without a fix`() {
        val sets = listOf(
            SampleSet(1, listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))),
            SampleSet(2, listOf(ChannelValue(GnssChannels.LAT.id, 10.0), ChannelValue(GnssChannels.LON.id, 10.0))),
            SampleSet(3, listOf(ChannelValue(GnssChannels.LAT.id, 10.001), ChannelValue(GnssChannels.LON.id, 10.0))),
        )
        assertEquals(10.0, assertNotNull(firstPosition(sets)).lat)
        assertTrue(replayLaps("x", sets, track).laps.isEmpty())
    }
}
