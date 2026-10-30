package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.LapPicks
import com.flappedear.betterdriver.analysis.review.pickedReview
import com.flappedear.betterdriver.analysis.review.pickerSession
import com.flappedear.betterdriver.analysis.review.pickerTracks
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compare laps from any sessions (FBBD-92, FBBD-379): the iPad's lap picker reads laps as Android's. */
class LapPickerTest {
    private val track = TrackJson.decode(demoAsset("jastrzab.track.json"))

    private fun demo(id: String): SessionData {
        val replay = SessionReplay.read(demoAsset("jastrzab.replay"), StartUtcMillis, RateHz)
        val loopFixes = demoAsset("jastrzab.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(StartUtcMillis, channels = GnssChannels.ALL)
        return SessionData(
            id, info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    @Test
    fun `two sessions at a track list their laps and two picked laps from different sessions compare`() {
        val first = checkNotNull(pickerSession(demo("one"), track, "Morning", null))
        val second = checkNotNull(pickerSession(demo("two"), track, "Afternoon", null))
        // The replay numbers its laps on its own, the out lap included, as Android's lap review.
        val best = checkNotNull(first.bestNumber)
        val timed = first.laps.count { it.timed }
        assertEquals(24, timed)
        assertTrue(first.detail.startsWith("$timed laps · best 1:49.9"), first.detail)
        assertEquals("one:$best", first.laps.single { it.number == best }.key)
        assertEquals("Best", first.laps.single { it.number == best }.notes(best = true))
        assertEquals("Out lap", first.laps.first().notes(best = false))
        val tracks = pickerTracks(listOf(track.id to second, track.id to first, "gone" to first), listOf(track))
        assertEquals(1, tracks.size)
        assertEquals(listOf("two", "one"), tracks.single().sessions.map { it.id })
        // Lap A from one session, lap B from the other, as Android's picker fills its slots.
        val picks = LapPicks().pick("one:$best").pick("two:5")
        assertEquals("one:$best", picks.a)
        assertEquals("two:5", picks.b)
        assertTrue(picks.ready)
        assertEquals("A", picks.slotOf("one:$best"))
        assertNull(picks.slotOf("one:5"))
        assertFalse(LapPicks().pick("one:5").next(isA = true).pick("two:3").ready)
        val review = checkNotNull(pickedReview(tracks, "one:$best", "two:5"))
        assertEquals(best, review.a.number)
        assertEquals(5, review.b.number)
        assertFalse(review.a.hasVideo)
        assertNull(pickedReview(tracks, "one:$best", "three:1"))
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
