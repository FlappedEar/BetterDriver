package com.flappedear.betterdriver.video

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Where a video comes from. */
@Serializable
enum class VideoSourceKind {
    /** The phone's own camera, the default source (FBBD-21). */
    PHONE,

    /** A GoPro started and stopped with the session through Open GoPro, HERO9 and later (FBBD-22, FBBD-105). */
    GOPRO,

    /** A GoPro started by hand whose files were added after the session (FBBD-22, FBBD-105). */
    GOPRO_FILES,

    /** Written by builds before FBBD-105 for a HERO11; read as [GOPRO]. Kept so older clip indexes still read. */
    GOPRO_HERO11,

    /** Written by builds before FBBD-105 for a HERO8; read as [GOPRO_FILES]. */
    GOPRO_HERO8,

    /** Another action camera started by hand whose files were added after the session (FBBD-120). */
    CAMERA_FILES,
    ;

    /** True for any camera other than the phone's (a GoPro or another action camera), older kinds included. */
    val isGoPro: Boolean get() = this != PHONE
}

/**
 * What a live source needs to know about the session it records with.
 *
 * [sessionClock] returns microseconds since the session started on the same monotonic clock the
 * recorder stamps samples with, so a source can stamp what it does in session time.
 * [startUtcMillis] is the session's start time on the phone's wall clock.
 * [videoDir] is where the source writes or downloads its files: `<session>/video`.
 */
class VideoSessionContext(
    val sessionId: String,
    val startUtcMillis: Long,
    val videoDir: File,
    val sessionClock: () -> Long,
)

/** What a live source is doing, for the UI. */
sealed interface VideoSourceState {
    data object Idle : VideoSourceState
    data object Starting : VideoSourceState
    data class Recording(val sinceSessionUs: Long) : VideoSourceState
    data object Stopping : VideoSourceState

    /** Stopped on its own. The session goes on; only this source's video is affected. */
    data class Failed(val reason: String) : VideoSourceState
}

/**
 * A camera that records along with a session: the phone camera or a HERO11.
 *
 * Contract, so that a camera can never affect data recording (FBBD-69):
 * - It runs in the UI process, never in the recorder's process, and never calls the recorder.
 * - [start] and [stop] never throw. A failure is reported through [state] as [VideoSourceState.Failed].
 * - [stop] returns whatever clips it has, even after a failure, so partial video is kept.
 * - Neither call blocks the caller's thread; slow work runs on the source's own dispatcher.
 */
interface LiveVideoSource {
    /** Stable id for this source within the app, e.g. "phone" or "gopro-<serial>". */
    val id: String
    val kind: VideoSourceKind
    val state: StateFlow<VideoSourceState>

    suspend fun start(session: VideoSessionContext)

    /**
     * Stops recording and returns the clips recorded since [start]. A source whose files stay on
     * the camera until they are downloaded after the session (a HERO11) may return none here and
     * add its clips to the [VideoIndex] when they arrive.
     */
    suspend fun stop(): List<VideoClip>
}
