@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.laps.gpsGapThreshold
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.live.GnssContinuity
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One GNSS continuity rule for live timing, the replay of saved laps and the analysis (audit A11). A
 * 25 Hz session whose fixes stop around the end of lap 3: across 2 s, no lap timed through the hole
 * can be the best lap or a reference in any of the three; across a third of a second (FBBD-215's
 * floor) every lap stays valid in all three.
 */
class GnssGapParityTest {
    private val sim = SimTrack.rectangle()
    private val track = sim.track()

    private fun data(sets: List<SampleSet>) = SessionData(
        "gaps", SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL), SessionState.FINISHED,
        GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
    )

    /** What each of the three makes of 5 laps at 25 Hz with [seconds] of fixes left out round lap 3's end. */
    private fun holed(seconds: Double): Holed {
        val sets = sim.samples(laps = 5, stepSeconds = 0.04)
        val lap3 = deriveSourceLapSession(telemetrySessionOf(data(sets), track)).timedLaps.single { it.number == 3 }
        val line = (lap3.endTelemetryTime * 1e6).toLong()
        val half = (seconds * 1e6 / 2).toLong()
        val hole = (line - half)..(line + half)
        val kept = sets.filterNot { it.tMicros in hole }
        return Holed(hole, kept)
    }

    private inner class Holed(val hole: LongRange, val sets: List<SampleSet>) {
        fun overlaps(startMicros: Long, endMicros: Long) = startMicros < hole.last && endMicros > hole.first

        val live = timeSession(track, sets.mapNotNull { it.toFix() }.asSequence())
        val replay = replayLaps("gaps", sets, track)
        val analysis = deriveSourceLapSession(telemetrySessionOf(data(sets), track)).timedLaps
        val analysisAcross = analysis.filter {
            overlaps((it.startTelemetryTime * 1e6).toLong(), (it.endTelemetryTime * 1e6).toLong())
        }
    }

    @Test
    fun `2 s without fixes across the line: no valid lap through the hole, live, replayed or analysed`() {
        val h = holed(2.0)
        val liveAcross = h.live.laps.filter { h.overlaps(it.startMicros, it.endMicros) }
        assertEquals(2, liveAcross.size, "the lap into the hole and the lap out of it are both counted")
        assertTrue(liveAcross.all { it.gnssGap && !it.countsForBest })
        val best = assertNotNull(h.live.best)
        assertFalse(h.overlaps(best.startMicros, best.endMicros))

        val replayAcross = h.replay.laps.filter { h.overlaps(it.lap.startMicros, it.lap.endMicros) }
        assertEquals(liveAcross, replayAcross.map { it.lap })
        val replayBest = assertNotNull(h.replay.best).lap
        assertFalse(h.overlaps(replayBest.startMicros, replayBest.endMicros))

        assertTrue(h.analysisAcross.isNotEmpty())
        assertTrue(h.analysisAcross.none { it.referenceEligible }, "analysis: ${h.analysisAcross}")
    }

    @Test
    fun `a third of a second without fixes is no gap in any of the three`() {
        val h = holed(0.3)
        assertTrue(h.live.laps.none { it.gnssGap })
        assertTrue(h.replay.laps.none { it.lap.gnssGap })
        assertTrue(h.analysisAcross.isNotEmpty())
        assertTrue(h.analysisAcross.all { it.referenceEligible }, "analysis: ${h.analysisAcross}")
    }

    @Test
    fun `the live timer and the analysis draw the line at the same step for 25 Hz and 1 Hz receivers`() {
        for ((stepMicros, expected) in listOf(40_000L to 0.5, 1_000_000L to 3.0)) {
            val times = DoubleArray(50) { it * stepMicros / 1e6 }
            val channel = TelemetryChannel("lat", times, DoubleArray(50) { 50.0 })
            assertEquals(expected, gpsGapThreshold(channel, channel), 1e-9)
            val live = GnssContinuity()
            repeat(20) { live.isGap(stepMicros) }
            assertEquals((expected * 1e6).toLong(), live.thresholdMicros())
        }
    }
}
