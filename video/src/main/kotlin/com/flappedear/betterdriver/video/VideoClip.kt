package com.flappedear.betterdriver.video

import kotlinx.serialization.Serializable

/** How a clip's offset to session time was found, best first. */
@Serializable
enum class AlignmentMethod {
    /** Frame timestamps taken on the same clock as the session (phone camera). */
    CAPTURE_CLOCK,

    /** The camera's own GPS track matched against the session's GNSS speed (GoPro GPMF). */
    GPS_MATCH,

    /** The camera's GPS UTC time compared with the session's GNSS UTC time, without matching. */
    GPS_UTC,

    /** The time the camera acknowledged the start command, on the session clock (HERO11). */
    COMMAND_ACK,

    /** The camera's file creation time on its own clock, which the app set from the phone. */
    CAMERA_CLOCK,

    /** Set by hand in review. */
    MANUAL,
}

@Serializable
data class Alignment(
    val method: AlignmentMethod,
    /** Rough bound on the error, in microseconds; an estimate, not a guarantee. */
    val uncertaintyUs: Long,
)

/** Where a clip's file is. */
@Serializable
enum class ClipStatus {
    /** In the session's video directory. */
    LOCAL,

    /** Still on the camera; [VideoClip.cameraPath] says where. */
    ON_CAMERA,
}

/**
 * One video file tied to one session.
 *
 * [sessionOffsetUs] is the session time, in microseconds, of the clip's first frame (video time
 * zero). It is negative when the camera started before the session. Session time of any frame is
 * `sessionOffsetUs + videoTimeUs`.
 *
 * GoPros split long recordings into chapter files; each chapter is its own clip with its own
 * offset, and [chapter] is its number starting at 1.
 */
@Serializable
data class VideoClip(
    /** File name inside the session's video directory. */
    val fileName: String,
    val kind: VideoSourceKind,
    val sourceId: String,
    val sessionOffsetUs: Long,
    val alignment: Alignment,
    val durationUs: Long? = null,
    val status: ClipStatus = ClipStatus.LOCAL,
    /** Path on the camera, such as "100GOPRO/GX010042.MP4", for clips still to download. */
    val cameraPath: String? = null,
    val chapter: Int = 1,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && fileName != "..") {
            "clip file name must be a plain file name: $fileName"
        }
        require(durationUs == null || durationUs >= 0) { "negative duration" }
        require(chapter >= 1) { "chapter numbers start at 1" }
    }

    fun sessionTimeUs(videoTimeUs: Long): Long = sessionOffsetUs + videoTimeUs

    fun videoTimeUs(sessionTimeUs: Long): Long = sessionTimeUs - sessionOffsetUs

    /** True when the clip has a frame at [sessionTimeUs]; unknown duration counts as open-ended. */
    fun covers(sessionTimeUs: Long): Boolean {
        val t = videoTimeUs(sessionTimeUs)
        return t >= 0 && (durationUs == null || t <= durationUs)
    }
}
