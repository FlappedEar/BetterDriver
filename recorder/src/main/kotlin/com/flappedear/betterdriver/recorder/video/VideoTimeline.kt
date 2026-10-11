package com.flappedear.betterdriver.recorder.video

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent

/**
 * One video file placed on the session clock: media time 0 is session time [startMicros].
 * [endMicros] is null when the end is unknown (the camera process died before it was logged
 * and no sync was seen).
 */
data class VideoClip(
    val camera: String,
    val file: String,
    val startMicros: Long,
    val endMicros: Long?,
) {
    /** Media time in this file at [sessionMicros]. Can be negative or past the end; see [VideoTimeline.locate]. */
    fun mediaMicrosAt(sessionMicros: Long) = sessionMicros - startMicros
}

/** A session time found in a clip. */
data class VideoPosition(val clip: VideoClip, val index: Int, val mediaMicros: Long)

/**
 * The video clips of a session, per camera, ordered by start.
 *
 * A clip's start is the earliest estimate of when its media time 0 happened. Each sync says
 * "at session time t the file held m of media"; the sync arrives after the media it reports, so
 * t - m can only be late, never early, and the smallest t - m over all syncs is the best estimate.
 * With no syncs, the video_start time is used.
 */
class VideoTimeline(clips: List<VideoClip>) {

    val clips: List<VideoClip> = clips.sortedWith(compareBy({ it.camera }, { it.startMicros }))

    val cameras: List<String> get() = clips.map { it.camera }.distinct()

    fun clips(camera: String): List<VideoClip> = clips.filter { it.camera == camera }

    /**
     * The clip of [camera] showing [sessionMicros], with its 0-based index among that camera's clips.
     * A clip with no known end runs until the next clip of the same camera starts.
     */
    fun locate(camera: String, sessionMicros: Long): VideoPosition? {
        val own = clips(camera)
        for (i in own.indices.reversed()) {
            val c = own[i]
            if (sessionMicros < c.startMicros) continue
            val end = c.endMicros ?: own.getOrNull(i + 1)?.startMicros ?: Long.MAX_VALUE
            return if (sessionMicros <= end) VideoPosition(c, i, c.mediaMicrosAt(sessionMicros)) else null
        }
        return null
    }

    companion object {
        /**
         * Clips from the live video events of a session, merged with [indexed]: the app's clip index
         * (`<session>/video/clips.json`, the `:video` module), which also holds clips added after the
         * session. An indexed clip wins over the events for the same camera and file; the events fill
         * in files the index lacks, such as one whose camera process died before indexing it.
         */
        fun from(events: List<RecorderEvent>, indexed: List<VideoClip> = emptyList()): VideoTimeline {
            val byFile = LinkedHashMap<Pair<String, String>, Acc>()
            for (e in events.filter { it.kind in VideoKinds }) {
                val f = VideoEventText.parse(e.text)
                val camera = f[VideoEventText.CAMERA]
                val file = f[VideoEventText.FILE]
                if (camera != null && file != null) byFile.getOrPut(camera to file) { Acc() }.add(e, f)
            }
            val indexedKeys = indexed.map { it.camera to it.file }.toSet()
            val live = byFile.filterKeys { it !in indexedKeys }
                .mapNotNull { (key, acc) -> acc.clip(key.first, key.second) }
            return VideoTimeline(indexed + live)
        }

        private val VideoKinds = setOf(EventKinds.VIDEO_START, EventKinds.VIDEO_SYNC, EventKinds.VIDEO_STOP)
    }

    /** What the events of one file say. */
    private class Acc {
        var start: Long? = null
        var syncStart: Long? = null
        var lastSync: Long? = null
        var stop: Long? = null

        fun add(e: RecorderEvent, fields: Map<String, String>) {
            when (e.kind) {
                EventKinds.VIDEO_START -> start = minOf(start ?: e.tMicros, e.tMicros)
                EventKinds.VIDEO_STOP -> stop = e.tMicros
                else -> fields[VideoEventText.MEDIA_US]?.toLongOrNull()?.let { media ->
                    syncStart = minOf(syncStart ?: Long.MAX_VALUE, e.tMicros - media)
                    lastSync = maxOf(lastSync ?: e.tMicros, e.tMicros)
                }
            }
        }

        fun clip(camera: String, file: String): VideoClip? =
            (syncStart ?: start)?.let { VideoClip(camera, file, it, stop ?: lastSync) }
    }
}
