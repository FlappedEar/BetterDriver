package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.TracePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FBBD-153: the review opened on two laps another screen asks for. */
class ReviewStartTest {
    private fun lap(session: String, number: Int, millis: Long): ReviewLap {
        val micros = millis * 1_000
        val points = listOf(
            TracePoint(0, 0, 0.0, 0.0, Xy(0.0, 0.0), 0.0, 100.0),
            TracePoint(micros, micros, 0.0, 0.0, Xy(1_000.0, 0.0), 1_000.0, 100.0),
        )
        return ReviewLap(LapTrace(session, Lap(number, LapKind.Timed, 0, micros, listOf(micros)), points), session, null)
    }

    private fun session(id: String, vararg laps: Pair<Int, Long>) =
        ReviewSession(id, id, "", laps.map { lap(id, it.first, it.second) }, null)

    private val newest = ReviewTrack("t1", "Track 1", listOf(session("s3", 1 to 80_000L, 2 to 79_000L)))
    private val older = ReviewTrack(
        "t2",
        "Track 2",
        listOf(session("s2", 1 to 90_000L, 2 to 91_000L, 3 to 89_500L), session("s1", 1 to 88_000L)),
    )
    private val tracks = listOf(newest, older)

    @Test
    fun `the review opens on the track and laps asked for`() {
        val start = ReviewStart(LapKey("s2", 3), LapKey("s2", 1), atFraction = 0.4)
        assertEquals("t2", firstTrack(tracks, start).id)
        assertEquals(LapKey("s2", 3) to LapKey("s2", 1), firstPair(older, start))
        assertEquals(0.4, start.atFor(LapKey("s2", 3), LapKey("s2", 1))!!, 1e-12)
        // Other laps, once picked, start at the line.
        assertNull(start.atFor(LapKey("s2", 1), LapKey("s2", 3)))
    }

    @Test
    fun `laps the review does not have leave it as it opens from Sessions`() {
        val missing = ReviewStart(LapKey("s2", 3), LapKey("s9", 1))
        assertEquals("t1", firstTrack(tracks, missing).id)
        assertEquals(older.defaultPair(), firstPair(older, missing))
        assertEquals("t1", firstTrack(tracks, null).id)
        assertEquals(newest.defaultPair(), firstPair(newest, null))
    }
}
