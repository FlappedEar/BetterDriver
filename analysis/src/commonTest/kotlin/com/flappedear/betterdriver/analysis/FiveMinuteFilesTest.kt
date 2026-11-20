package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.LapVideoSource
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.PhoneFileLength
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.overlay.totalMicros
import com.flappedear.betterdriver.timing.overlay.videoCuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FBBD-468: phone video is cut into 5-minute files on both apps. A lap that spans a file change
 * plays on in review and in the data video, with at most the frame lost at the change missing.
 */
class FiveMinuteFilesTest {
    private val frame = 33_333L
    private val five = PhoneFileLength.MICROS

    // Session 0 to 5 min in the first file, the next from one frame after its end.
    private val first = VideoClip(CameraIds.PHONE, "phone_0001.mp4", 0, five)
    private val second = VideoClip(CameraIds.PHONE, "phone_0002.mp4", five + frame, 2 * five + frame)
    private val clips = listOf(second, first)
    private val lengths = mapOf(first.file to five, second.file to five)

    @Test
    fun reviewFollowsALapIntoTheNextFile() {
        val video = LapVideoSource(CameraIds.PHONE, clips, "v")
        val before = checkNotNull(video.at(five - 1_000_000))
        assertEquals(first.file, before.clip.file)
        assertEquals(five - 1_000_000, before.mediaMicros)
        val after = checkNotNull(video.at(five + 2_000_000))
        assertEquals(second.file, after.clip.file)
        assertEquals(2_000_000 - frame, after.mediaMicros)
        // The frame lost at the change shows nothing rather than a wrong frame.
        assertNull(video.at(five + frame / 2))
    }

    @Test
    fun theDataVideoOfALapAcrossTheChangeIsBothFilesBackToBack() {
        // A 90 s lap from 4:00 to 5:30.
        val start = 240_000_000L
        val end = 330_000_000L
        val cuts = videoCuts(clips, { lengths[it.file] }, start, end)
        assertEquals(listOf(first.file, second.file), cuts.map { it.clip.file })
        assertEquals(start, cuts[0].fromMicros)
        assertEquals(five, cuts[0].toMicros)
        assertEquals(0L, cuts[1].fromMicros)
        assertEquals(end - second.startMicros, cuts[1].toMicros)
        assertEquals(end - start - frame, cuts.totalMicros(), "only the lost frame is missing")
        // The overlay stays on the session clock after the change.
        assertEquals(five + frame, cuts[1].sessionMicrosAt(cuts[1].outputStartMicros))
    }
}
