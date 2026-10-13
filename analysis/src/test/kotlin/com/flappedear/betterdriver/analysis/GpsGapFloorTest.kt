@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.MinimumGpsGapSeconds
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A few missed fixes are not a loss of GPS (FBBD-215): a 25 Hz receiver that skips the odd fix and
 * then three in a row kept the lap out of the analysis, as three sample intervals were the longest
 * step still continuous. Steps up to [MinimumGpsGapSeconds] are; a real loss of signal still is a gap.
 */
class GpsGapFloorTest {
    private val sim = SimTrack.rectangle()

    /** Five laps at 25 Hz with the fixes of lap 3 from [from] seconds into it for [seconds] left out. */
    private fun laps(from: Double, seconds: Double): List<Pair<Int, LapReferenceIssue>> {
        val sets = sim.samples(laps = 5, stepSeconds = 0.04)
        val all = deriveSourceLapSession(telemetrySessionOf(data(sets), sim.track())).timedLaps
        val lap3 = all.single { it.number == 3 }
        val start = ((lap3.startTelemetryTime + from) * 1e6).toLong()
        val end = start + (seconds * 1e6).toLong()
        val kept = sets.filterNot { it.tMicros in start until end }
        return deriveSourceLapSession(telemetrySessionOf(data(kept), sim.track())).timedLaps
            .map { it.number to it.referenceIssue }
    }

    private fun data(sets: List<SampleSet>) = SessionData(
        "gaps", SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL), SessionState.FINISHED,
        GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
    )

    @Test
    fun `three missed fixes in a row at 25 Hz keep the lap`() {
        val issues = laps(from = 20.0, seconds = 0.12).toMap()
        assertEquals(LapReferenceIssue.None, issues[3])
    }

    @Test
    fun `a third of a second without fixes keeps every sector time`() {
        val sets = sim.samples(laps = 5, stepSeconds = 0.04)
        val full = analyzeSession(telemetrySessionOf(data(sets), sim.track()))
        val lap3 = full.laps.timedLaps.single { it.number == 3 }
        // Every segment boundary of lap 3, crossed in a 0.3 s hole.
        for (segment in full.segments!!.segments) {
            val crossing = full.traces[full.laps.timedLaps.indexOf(lap3)].flatMap { it.samples }
                .first { it.progressMeters >= segment.startProgressMeters }.telemetryTime
            val start = ((crossing - 0.15) * 1e6).toLong()
            val kept = sets.filterNot { it.tMicros in start until start + 300_000 }
            val holed = analyzeSession(telemetrySessionOf(data(kept), sim.track()))
            val times = holed.lapTimes[holed.laps.timedLaps.indexOfFirst { it.number == 3 }]
            for (s in full.segments!!.segments) {
                assertNotNull(times.sector(s.id)?.seconds, "${s.id} with fixes missing round ${segment.id}'s start")
            }
        }
    }

    @Test
    fun `a second without fixes is still a GPS gap`() {
        val issues = laps(from = 20.0, seconds = 1.0).toMap()
        assertEquals(LapReferenceIssue.GpsGap, issues[3])
    }
}
