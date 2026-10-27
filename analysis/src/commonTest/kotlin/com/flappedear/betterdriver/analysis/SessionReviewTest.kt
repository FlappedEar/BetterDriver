package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.reviewOf
import com.flappedear.betterdriver.analysis.review.reviewSession
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
import kotlin.test.assertTrue

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
        // The same top 3 as DemoParityTest's line plan focus, from the Map tab's ranked corners.
        val line = checkNotNull(review.line)
        assertEquals(
            listOf("T11:0.281", "T1:0.112"),
            line.ranked.take(2).map { "${it.tag}:${fixed(it.gainSeconds, 3)}" },
        )
    }

    @Test
    fun `compare opens on the second fastest lap against the fastest by distance`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val compare = checkNotNull(reviewed.defaultCompare())
        val laps = reviewed.review.laps
        val second = laps.filter { it.counts && !it.fastest }.minBy { it.seconds }
        assertEquals(18, compare.referenceNumber)
        assertEquals(second.number, compare.otherNumber)
        assertEquals(19, compare.otherNumber)
        assertEquals("2027", fixed(compare.lengthMeters, 0))
        assertEquals(12, compare.corners.size)
        // At the line the delta is the gap between the two lap times.
        assertEquals(fixed(second.deltaSeconds, 2), fixed(compare.delta.last().last().value, 2))
        assertEquals(compare, reviewed.compare(compare.otherIndex))
        // Android's screen model, for the Corners and Car tabs: the same laps and corners.
        val screen = checkNotNull(reviewed.defaultScreen())
        assertEquals(19, screen.compare?.otherNumber)
        assertEquals(12, screen.corners.size)
        assertEquals(screen.sectors.size, screen.sectors.count { it.bestSeconds != null })
        assertEquals("108.201", fixed(screen.theoreticalMillis!! / 1000.0, 3))
        assertTrue(compare.corners.isNotEmpty())
        assertTrue(compare.referenceSpeed.isNotEmpty() && compare.otherSpeed.isNotEmpty())
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
