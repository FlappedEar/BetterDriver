package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.reviewOf
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The iPad review's numbers for a session (FBBD-331) are the same as DemoParityTest's on every platform. */
class SessionReviewTest {
    private val track = TrackJson.decode(demoAsset("jastrzab.track.json"))

    private fun demo(): SessionData {
        val replay = SessionReplay.read(demoAsset("jastrzab.replay"), StartUtcMillis, RateHz)
        val loopFixes = demoAsset("jastrzab.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(StartUtcMillis, channels = GnssChannels.ALL)
        return SessionData(
            "jastrzab", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(),
            null, 0, 0,
        )
    }

    @Test
    fun `the demo session is found at its track by position and reviewed as on Android`() {
        val review = reviewOf(demo(), listOf(track))
        assertEquals(track.id, review.track?.id)
        assertEquals(23, review.laps.size)
        assertEquals(18, review.laps.single { it.fastest }.number)
        assertEquals("109.906", fixed(review.bestLapSeconds!!, 3))
        assertEquals("108.201", fixed(review.theoreticalBestSeconds!!, 3))
        assertEquals("0.000", fixed(review.laps.single { it.fastest }.deltaSeconds, 3))
    }

    @Test
    fun `a session away from every saved track has no laps`() {
        val review = reviewOf(demo(), emptyList())
        assertNull(review.track)
        assertEquals(emptyList(), review.laps)
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
