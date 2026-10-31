package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.lastFocusProof
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.review.reviewSession
import com.flappedear.betterdriver.feature.analysis.CoachingStatus
import com.flappedear.betterdriver.feature.analysis.coachingCard
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The review keeps each session in the coaching history, with the track's corners (FBBD-364). */
class ReviewCoachingHistoryTest {
    private var track = TrackJson.decode(demoAsset("jastrzab.track.json"))

    private fun demo(id: String, start: Long): SessionData {
        val replay = SessionReplay.read(demoAsset("jastrzab.replay"), start, RateHz)
        val loopFixes = demoAsset("jastrzab.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(start, track = track.id, channels = GnssChannels.ALL)
        return SessionData(
            id, info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(),
            null, 0, 0,
        )
    }

    @Test
    fun `each reviewed session is kept in the history under the corners the track keeps`() {
        val dir = Files.createTempDirectory("history").toFile()
        val store = CoachingHistoryStore(dir.path)
        val plans = LinePlanStore(dir.resolve("lines").path)
        val keep = { _: String, _: TrackRevision?, made: TrackRevision ->
            track = track.copy(identity = made)
            made
        }
        val shown = listOf("s1", "s2").mapIndexed { n, id ->
            val reviewed = reviewSession(demo(id, StartUtcMillis + n * DayMillis), listOf(track), keep)
            assertNotNull(reviewed.identity, "$id identified")
            reviewed.linePlan?.let(plans::save)
            reviewed.keepCoaching(store, CoachedSession())
        }
        // The first has nothing before it; the second shows what came of the first's focus: the
        // same laps again, so no gain in any of its three corners.
        assertTrue(shown[0].empty)
        assertEquals(
            listOf("Corner 7", "Corner 6", "Corner 5"),
            shown[1].attempts.map { it.ask.name },
        )
        assertTrue(shown[1].attempts.all { it.source.sessionId == "s1" && it.verdict == FocusVerdict.NoGain })
        // Measured through both line plans, as the card says it: the same time, nothing won back.
        val proof = assertNotNull(lastFocusProof(plans, track.id, "s2", shown[1]))
        val card = coachingCard(shown[1], DayZone.Utc, proof)
        assertEquals("0.00 s", card.wonBack)
        assertTrue(card.attempts.all { it.measured && it.status == CoachingStatus.Same }, "${card.attempts}")
        assertNotNull(track.identity)
        assertEquals(listOf("s1", "s2"), store.history(track.id).map { it.sessionId })
        dir.deleteRecursively()
    }

    private companion object {
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
        const val StartUtcMillis = 1_790_000_000_000L
        const val DayMillis = 86_400_000L
    }
}
