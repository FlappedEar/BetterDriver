package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Phone video progress reaches the clip index (FBBD-21). */
class ClipIndexerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun firstProgressReportIsDueAtOnceThenAboutOnceASecond() {
        assertTrue(syncDue(0, null))
        assertTrue(syncDue(5_000_000, null))
        assertFalse(syncDue(5_999_999, 5_000_000))
        assertTrue(syncDue(6_000_000, 5_000_000))
    }

    /**
     * A recording of about 3 s whose progress reports arrive 10 to 90 ms late: the clip starts at the
     * earliest "now minus media", not at the late start report, and has a length before it ends.
     */
    @Test
    fun progressReportsPlaceAndMeasureTheClip() {
        val session = tmp.newFolder("s1")
        val index = VideoIndex(session)
        val clips = ClipIndexer(index)
        val file = "phone_0001.mp4"
        clips.started(file, 1_050_000)
        var last: Long? = null
        // CameraX reports progress many times a second; the camera passes on about one a second.
        for ((now, media) in listOf(
            1_200_000L to 150_000L,
            2_030_000L to 1_000_000L,
            2_100_000L to 1_070_000L,
            3_010_000L to 2_000_000L,
            4_090_000L to 3_000_000L,
        )) {
            if (syncDue(now, last)) {
                last = now
                clips.synced(file, now, media)
            }
        }
        // Rewritten when the offset improves (at 3.01 s), not for every report.
        val clip = awaitClip(index) { it.sessionOffsetUs == 1_010_000L }
        assertEquals(2_000_000L, clip.durationUs)
        clips.finished(file, 3_050_000)
        assertEquals(3_050_000L, awaitClip(index) { it.durationUs == 3_050_000L }.durationUs)
    }

    /**
     * FBBD-218: a report that also says when the newest frame was captured places the clip by the
     * frame, which is closer than the report's own time; a report without one still counts.
     */
    @Test
    fun frameTimesPlaceTheClipCloserThanTheReports() {
        val session = tmp.newFolder("s2")
        val index = VideoIndex(session)
        val clips = ClipIndexer(index)
        val file = "phone_0001.mp4"
        clips.started(file, 1_000_000)
        clips.synced(file, 2_090_000, 1_000_000, frameMicros = 2_020_000)
        assertEquals(1_020_000L, awaitClip(index) { it.sessionOffsetUs == 1_020_000L }.sessionOffsetUs)
        // A later report with no frame time and a later estimate changes nothing.
        clips.synced(file, 3_080_000, 2_000_000)
        clips.finished(file, 2_000_000)
        assertEquals(1_020_000L, awaitClip(index) { it.durationUs == 2_000_000L }.sessionOffsetUs)
    }

    private fun awaitClip(index: VideoIndex, done: (VideoClip) -> Boolean): VideoClip {
        val until = System.currentTimeMillis() + WaitMillis
        while (System.currentTimeMillis() < until) {
            index.clips().singleOrNull()?.takeIf(done)?.let { return it }
            Thread.sleep(PollMillis)
        }
        throw AssertionError("clip index never got there: ${index.clips()}")
    }

    private companion object {
        const val WaitMillis = 5_000L
        const val PollMillis = 10L
    }
}
