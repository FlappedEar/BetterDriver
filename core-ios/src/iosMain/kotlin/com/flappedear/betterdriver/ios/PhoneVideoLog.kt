package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoEventText
import com.flappedear.betterdriver.recorder.video.VideoFiles

/**
 * The iPhone camera's files in a [LiveSession], logged as the Android recorder logs its phone camera
 * (FBBD-21): files video/phone_0001.mp4 and on, a video_start at the session time of each file's
 * first frame, a video_sync about once a second with the media recorded and the session time of the
 * newest frame, and a video_stop. Review, VBO export and the clip placement (VideoTimeline) read
 * them the same way on both platforms. All times are microseconds on the session clock.
 */
class PhoneVideoLog(private val session: LiveSession) {
    private val camera = CameraIds.PHONE

    /** The folder the camera writes its files to. */
    val videoDir: String get() = "${session.dir}/${VideoFiles.DIR}"

    /** Name of the [index]th (1-based) file. */
    fun fileName(index: Int): String = VideoFiles.liveName(camera, index)

    fun nowMicros(): Long = session.nowMicros()

    /** [file]'s media time 0 is at session time [startMicros]; it records at [fps]. */
    fun started(file: String, startMicros: Long, fps: Int) =
        session.eventAt(startMicros, EventKinds.VIDEO_START, VideoEventText.started(camera, file, fps))

    /** At session time [atMicros], [file] held [mediaMicros] of media; its newest frame was taken at [frameMicros]. */
    fun synced(file: String, atMicros: Long, mediaMicros: Long, frameMicros: Long) =
        session.eventAt(atMicros, EventKinds.VIDEO_SYNC, VideoEventText.synced(camera, file, mediaMicros, frameMicros))

    /** [file] ended now; [reason] such as "user", "interrupted" or "error". */
    fun stopped(file: String, reason: String) =
        session.eventAt(session.nowMicros(), EventKinds.VIDEO_STOP, VideoEventText.stopped(camera, file, reason))
}
