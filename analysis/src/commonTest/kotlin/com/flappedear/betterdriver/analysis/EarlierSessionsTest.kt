package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.reviewSession
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.progressBestText
import com.flappedear.betterdriver.analysis.summary.readEarlierSessions
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The summary's card against earlier sessions (FBBD-122, FBBD-377), read by the shared core the same
 * on Android and iOS: only finished sessions before this one at its track, the session before in full.
 */
class EarlierSessionsTest {
    private val track = TrackJson.decode(demoAsset("jastrzab.track.json"))
    private val replay = demoAsset("jastrzab.replay")

    private fun data(id: String, start: Long): SessionData {
        val session = SessionReplay.read(replay, start, RateHz)
        val loopFixes = replay.lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, session.fix(it)) }
        val info = SessionInfo(start, channels = GnssChannels.ALL)
        return SessionData(
            id, info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    private fun stored(id: String, day: Long, state: SessionState = SessionState.FINISHED) =
        StoredSession(id, id, SessionInfo(StartUtcMillis + day * DayMillis), state)

    @Test
    fun onlyFinishedSessionsBeforeThisOneAtItsTrackAreRead() {
        // Newest first, as both stores list them.
        val list = listOf(
            stored("later", 5),
            stored("current", 3),
            stored("recording", 2, SessionState.RECORDING),
            stored("broken", 2),
            stored("before", 1),
            stored("first", 0),
        )
        val read = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val earlier = readEarlierSessions(
            list, "current", StartUtcMillis + 3 * DayMillis, track, listOf(track),
            read = { s, samples ->
                if (samples) read += s.id
                check(s.id != "broken") { "damaged" }
                data(s.id, s.info.startUtcMillis)
            },
            label = { "day ${(it - StartUtcMillis) / DayMillis}" },
            onError = { s, _ -> failed += s.id },
        )
        assertEquals(listOf("before", "first"), read)
        assertEquals(listOf("broken"), failed)
        assertEquals(listOf("day 1", "day 0"), earlier.sessions.map { it.label })
        assertFalse(earlier.limited)
        // Only the session before keeps its samples, for its corner times.
        assertNotNull(earlier.previous)
        assertTrue(earlier.sessions.all { it.laps.lapTraces.isEmpty() })

        val progress = assertNotNull(reviewSession(data("current", StartUtcMillis + 3 * DayMillis), listOf(track))
            .progress(earlier))
        assertEquals(2, progress.earlierSessions)
        assertEquals(0.0, progress.bestDeltaSeconds)
        assertEquals("day 1", progress.previousLabel)
        assertTrue(progress.changes.isNotEmpty())
        assertEquals("Best lap against your best here", progressBestText(progress))
    }

    @Test
    fun theBestLapIsWordedTheSameOnEveryPlatform() {
        assertEquals("First session at this track.", progressBestText(SessionProgress()))
        assertEquals(
            "No recent earlier session at this track.",
            progressBestText(SessionProgress(earlierLimited = true)),
        )
        assertEquals(
            "No counted lap in the earlier 2 sessions here.",
            progressBestText(SessionProgress(earlierSessions = 2)),
        )
        val quicker = SessionProgress(earlierSessions = 2, bestLapSeconds = 100.0, earlierBestSeconds = 101.0)
        assertEquals("New best lap here", progressBestText(quicker))
        assertEquals("New best of your last 2 sessions here", progressBestText(quicker.copy(earlierLimited = true)))
        assertEquals(
            "Best lap against your last 2 sessions here",
            progressBestText(quicker.copy(earlierLimited = true, bestLapSeconds = 102.0)),
        )
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val DayMillis = 24 * 3_600_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
