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
