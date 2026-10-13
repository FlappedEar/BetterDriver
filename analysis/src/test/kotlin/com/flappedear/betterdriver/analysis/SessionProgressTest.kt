package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.laps.rankLaps
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.analysis.summary.EarlierSession
import com.flappedear.betterdriver.analysis.summary.summarizeProgress
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionProgressTest {
    private fun laps(vararg seconds: Double, gap: Int? = null): LapSession {
        val timed = seconds.mapIndexed { i, s ->
            TimedLap(
                i + 1, i * 100.0, i * 100.0 + s, s,
                referenceIssue = if (i == gap) LapReferenceIssue.GpsGap else LapReferenceIssue.None,
            )
        }
        val (ranked, fastest) = rankLaps(timed)
        return LapSession(LapSessionStatus.Available, timedLaps = ranked, fastestLapIndex = fastest)
    }

    @Test
    fun `best lap against the quickest earlier session, spread against the session before`() {
        val progress = summarizeProgress(
            laps(82.0, 81.0, 81.5, 80.0, gap = 3), // the 80.0 lap has a GPS gap and does not count
            listOf(
                EarlierSession("no laps", LapSession(LapSessionStatus.InsufficientPasses)),
                EarlierSession("before", laps(82.4, 82.0, 83.0, 82.2)),
                EarlierSession("morning", laps(80.9, 84.0)),
            ),
        )
        assertEquals(3, progress.earlierSessions)
        assertEquals(3, progress.eligibleLapCount)
        assertEquals(81.0, progress.bestLapSeconds)
        assertEquals(80.9, progress.earlierBestSeconds)
        assertEquals("morning", progress.earlierBestLabel)
        assertEquals(0.1, progress.bestDeltaSeconds!!, 1e-12)
        assertFalse(progress.newBest)
        assertEquals("before", progress.previousLabel)
        assertEquals(82.0, progress.previousBestSeconds)
        // Only the counted laps: 81.0, 81.5 and 82.0, against 82.4, 82.0, 83.0 and 82.2.
        val now = summarizeConsistency(listOf(82.0, 81.0, 81.5)).interquartileRange!!
        val before = summarizeConsistency(listOf(82.4, 82.0, 83.0, 82.2)).interquartileRange!!
        assertEquals(now, progress.lapSpreadSeconds!!, 1e-12)
        assertEquals(before, progress.previousSpreadSeconds!!, 1e-12)
        assertEquals(now - before, progress.spreadDeltaSeconds!!, 1e-12)
        // The typical lap is the median of the counted laps, the slow lap does not drag it (FBBD-175).
        assertEquals(81.5, progress.lapMedianSeconds!!, 1e-12)
    }

    /**
     * Consistency by median and interquartile range (FBBD-175), as Telemetry's consistency.dart
     * defines them: quartiles interpolated linearly between the ordered laps. Reference values worked
     * out by hand for eight laps with a warm-up and a traffic lap; the mean and the standard deviation
     * would both be dragged by those two, the median and the spread are not.
     */
    @Test
    fun `consistency is the median and the interquartile range of the counted laps`() {
        val times = listOf(95.0, 81.4, 81.0, 81.2, 81.6, 88.0, 81.1, 81.3)
        val progress = summarizeProgress(laps(*times.toDoubleArray()), emptyList())
        // Ordered: 81.0 81.1 81.2 81.3 81.4 81.6 88.0 95.0. Q1 at 1.75: 81.1 + 0.75 × 0.1 = 81.175;
        // median at 3.5: 81.35; Q3 at 5.25: 81.6 + 0.25 × 6.4 = 83.2.
        assertEquals(81.35, progress.lapMedianSeconds!!, 0.01)
        assertEquals(83.2 - 81.175, progress.lapSpreadSeconds!!, 0.01)
        assertNull(summarizeProgress(laps(81.0, 82.0), emptyList()).lapMedianSeconds)
    }

    @Test
    fun `a first session has nothing to compare and a quicker lap is a new best`() {
        val first = summarizeProgress(laps(81.0, 82.0), emptyList())
        assertNull(first.bestDeltaSeconds)
        assertNull(first.previousLabel)
        assertNull(first.lapSpreadSeconds) // two laps are too few
        assertFalse(first.newBest)
        val quicker = summarizeProgress(laps(80.5), listOf(EarlierSession("before", laps(81.0))))
        assertTrue(quicker.newBest)
        assertEquals(-0.5, quicker.bestDeltaSeconds)
    }
}
