package com.flappedear.betterdriver.review

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoSourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Clips of the clip index on the recorder's timeline, as the review and the VBO export read them. */
class IndexedClipTest {
    private val minute = 60_000_000L
    private val front = CameraIds.goPro("front")
    private val rear = CameraIds.goPro("rear")

    private fun clip(file: String, source: String, kind: VideoSourceKind, offsetUs: Long, durationUs: Long) =
        VideoClip(file, kind, source, offsetUs, Alignment(AlignmentMethod.GPS_MATCH, 50_000), durationUs)

    /**
     * Two GoPros of the same kind, both recording: the front one for 10 minutes, the rear one from
     * minute 1 to 5. At minute 7 only the front one has video, and its own timeline finds it.
     */
    @Test
    fun twoGoProsOfOneKindStayTwoCameras() {
        val clips = listOf(
            clip("$front-GX010001.MP4", front, VideoSourceKind.GOPRO, 0, 10 * minute),
            clip("$rear-GX010007.MP4", rear, VideoSourceKind.GOPRO, minute, 4 * minute),
        ).map(::recorderClip)
        val timeline = VideoTimeline(clips)
        assertEquals(listOf(front, rear), timeline.cameras)
        assertEquals("$front-GX010001.MP4", timeline.locate(front, 7 * minute)?.clip?.file)
        assertEquals(7 * minute, timeline.locate(front, 7 * minute)?.mediaMicros)
        assertEquals("$rear-GX010007.MP4", timeline.locate(rear, 2 * minute)?.clip?.file)
        assertEquals(null, timeline.locate(rear, 7 * minute))
    }

    /** The phone's indexed clip and its live video events are one clip; the index wins. */
    @Test
    fun phoneClipMergesWithItsLiveEvents() {
        val file = "phone_0001.mp4"
        val events = listOf(
            RecorderEvent(1_050_000, EventKinds.VIDEO_START, "camera=phone file=$file"),
            RecorderEvent(9_000_000, EventKinds.VIDEO_STOP, "camera=phone file=$file reason=user"),
        )
        val indexed = clip(file, CameraIds.PHONE, VideoSourceKind.PHONE, 1_010_000, 7_900_000)
        val timeline = VideoTimeline.from(events, listOf(recorderClip(indexed)))
        val only = timeline.clips.single()
        assertEquals(CameraIds.PHONE, only.camera)
        assertEquals(1_010_000L, only.startMicros)
        assertEquals(8_910_000L, only.endMicros)
    }
}
