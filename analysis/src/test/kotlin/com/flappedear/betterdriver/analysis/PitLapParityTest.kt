@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review leaves out the laps live timing leaves out (review v1.34.1 F3, FBBD-482). Before, review
 * ranked laps by time and GPS alone: a lap through the pit lane, which live timing calls an in lap
 * and never its best, could be review's best lap. Here the fastest lap of the session drives through
 * the pit entry line; live timing and review must agree on every lap and on the best.
 */
class PitLapParityTest {
    private val sim = SimTrack.rectangle()
    private val heading = (sim.path[1] - sim.path[0]).let { it * (1.0 / it.length()) }
    private val normal = Xy(-heading.y, heading.x)

    /** A line across the first straight [along] m past the start line, [near] to [far] m to its left. */
    private fun sideLine(along: Double, near: Double, far: Double): TrackLine {
        val center = heading * along
        // The left end is the one further left, as on the start/finish line: forwards is along the straight.
        return TrackLine(sim.geo(center + normal * far), sim.geo(center + normal * near))
    }

    private val pitEntry = sideLine(130.0, 20.0, 40.0)
    private val pitExit = sideLine(130.0, -40.0, -20.0)

    private fun data(sets: List<SampleSet>) = SessionData(
        "pit", SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL), SessionState.FINISHED,
        GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
    )

    /**
     * [sets] with lap [lap] (from the first line crossing, from 1) moved [offset] m to the left in the
     * middle of the first straight: through a pit line there, back on the racing line by its end.
     */
    private fun detour(sets: List<SampleSet>, lap: Int, offset: Double): List<SampleSet> {
        val laps = deriveSourceLapSession(telemetrySessionOf(data(sets), sim.track())).timedLaps
        val window = laps.single { it.number == lap }
        val from = (window.startTelemetryTime * 1e6).toLong()
        val to = (window.endTelemetryTime * 1e6).toLong()
        return sets.map { set ->
            if (set.tMicros !in from..to) return@map set
            val fix = assertNotNull(set.toFix())
            val p = sim.frame.toXy(fix.lat, fix.lon)
            val along = p.dot(heading)
            val across = p.dot(normal)
            if (abs(across) > 5.0 || along !in 30.0..230.0) return@map set
            val ramp = min(1.0, min(along - 30.0, 230.0 - along) / 50.0)
            val g = sim.geo(p + normal * (offset * ramp))
            set.copy(
                values = set.values.map {
                    when (it.channelId) {
                        GnssChannels.LAT.id -> ChannelValue(it.channelId, g.lat)
                        GnssChannels.LON.id -> ChannelValue(it.channelId, g.lon)
                        else -> it
                    }
                },
            )
        }
    }

    private class Both(val live: List<Lap>, val best: Lap?, val review: List<TimedLap>)

    private fun both(track: Track, sets: List<SampleSet>): Both {
        val book = timeSession(track, sets.mapNotNull { it.toFix() }.asSequence())
        val review = deriveSourceLapSession(telemetrySessionOf(data(sets), track)).timedLaps
        return Both(book.laps, book.best, review)
    }

    /** Every review lap is the live lap that ends with it, and counts for the best exactly when that one does. */
    private fun assertSameLaps(b: Both) {
        assertTrue(b.review.isNotEmpty())
        for (r in b.review) {
            val l = b.live.single { abs(it.endMicros / 1e6 - r.endTelemetryTime) < 0.05 }
            assertEquals(l.countsForBest, r.referenceEligible, "live $l, review $r")
            val expected = when (l.kind) {
                LapKind.In -> LapReferenceIssue.InLap
                LapKind.Out -> LapReferenceIssue.OutLap
                LapKind.Timed -> LapReferenceIssue.None
            }
            assertEquals(expected, r.referenceIssue, "live $l, review $r")
        }
        val best = assertNotNull(b.best)
        val reviewBest = assertNotNull(b.review.filter { it.referenceEligible }.minByOrNull { it.durationSeconds })
        assertEquals(best.endMicros / 1e6, reviewBest.endTelemetryTime, 0.05)
    }

    @Test
    fun `the fastest lap through the pit entry is an in lap in review as live, and the next is an out lap`() {
        // No pit exit line: the pit lane rejoins before the start/finish line, so the next lap is an out lap.
        val track = sim.track().copy(pitEntry = pitEntry)
        val sets = detour(sim.samples(laps = 5, stepSeconds = 0.04), lap = 4, offset = 30.0)
        val b = both(track, sets)
        assertEquals(LapReferenceIssue.InLap, b.review.single { it.number == 4 }.referenceIssue)
        assertEquals(LapReferenceIssue.OutLap, b.review.single { it.number == 5 }.referenceIssue)
        assertSameLaps(b)
        // Lap 5 would be the best (each lap is quicker); it is the out lap, so the best is lap 3.
        assertEquals(3, b.review.filter { it.referenceEligible }.minBy { it.durationSeconds }.number)
    }

    @Test
    fun `with a pit exit line the lap that crosses it is the out lap and the one after is timed`() {
        val track = sim.track().copy(pitEntry = pitEntry, pitExit = pitExit)
        val laps = sim.samples(laps = 6, stepSeconds = 0.04)
        val sets = detour(detour(laps, lap = 3, offset = 30.0), lap = 4, offset = -30.0)
        val b = both(track, sets)
        assertEquals(
            listOf(
                LapReferenceIssue.None, LapReferenceIssue.None, LapReferenceIssue.InLap,
                LapReferenceIssue.OutLap, LapReferenceIssue.None, LapReferenceIssue.None,
            ),
            b.review.map { it.referenceIssue },
        )
        assertSameLaps(b)
    }

    @Test
    fun `without pit lines review times every lap as before`() {
        val sets = detour(sim.samples(laps = 5, stepSeconds = 0.04), lap = 4, offset = 30.0)
        val b = both(sim.track(), sets)
        assertTrue(b.review.all { it.referenceIssue == LapReferenceIssue.None }, "review: ${b.review}")
        assertSameLaps(b)
    }
}
