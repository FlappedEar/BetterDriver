package com.flappedear.betterdriver.video

import kotlinx.coroutines.flow.StateFlow
import java.io.File

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
