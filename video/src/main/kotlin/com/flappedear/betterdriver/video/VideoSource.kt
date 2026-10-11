package com.flappedear.betterdriver.video

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Where a video comes from. */
@Serializable
enum class VideoSourceKind {
    /** The phone's own camera, the default source (FBBD-21). */
    PHONE,

    /** GoPro HERO11 Black, started and stopped with the session through Open GoPro (FBBD-22). */
    GOPRO_HERO11,

    /** GoPro HERO8 Black: no Open GoPro support, so it is started by hand and imported afterwards (FBBD-22). */
    GOPRO_HERO8,
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
     * Stops recording and returns the clips recorded since [start]. A source that downloads its
     * files later (a HERO11 over Wi-Fi) returns clips whose [VideoClip.fileName] is not yet in
     * the video directory and whose [VideoClip.status] is [ClipStatus.ON_CAMERA].
     */
    suspend fun stop(): List<VideoClip>
}
