package com.flappedear.betterdriver.gopro

import kotlinx.serialization.Serializable

/**
 * What the app knows about an Open GoPro recording made with a session, kept until its files are
 * downloaded: when the camera acknowledged start and stop on the session clock and on the phone's
 * wall clock, and the UTC offset the camera's clock was set to.
 */
@Serializable
data class GoProRecording(
    val sourceId: String,
    val serial: String = "",
    val startAckSessionUs: Long,
    val startAckUtcMillis: Long,
    val stopAckSessionUs: Long? = null,
    val stopAckUtcMillis: Long? = null,
    /** The UTC offset the app set on the camera's clock, or null if setting the clock failed. */
    val cameraUtcOffsetMinutes: Int? = null,
    /** The video mode the app set ("1080p · 60"), or null if the camera kept its own (FBBD-88). */
    val videoMode: String? = null,
    val downloaded: Boolean = false,
)
