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
 * A phone sync also says when the camera's newest frame was captured (f, from its sensor
 * timestamp): that frame is at least as new as the media reported, so f - m is late too, and
 * closer; the smallest of both is used. On Arek's 2026-10-13 drive f - m was 50 to 94 ms earlier
 * than t - m in each file, which the data quality card showed as an 88 ms start sync error
 * (FBBD-218). With no syncs, the video_start time is used.
 *
 * f - m is not always late, though: on Arek's 2026-10-17 drive, under Android's thermal emergency,
 * single reports came up to 7 frames early (a stale frame time against newer media), and the
 * smallest one placed a file 100 ms early. So with frame times a file is placed by
 * [confirmedLowest] of its first minute of them.
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
        val placement = PhonePlacement()
        var lastSync: Long? = null
        var stop: Long? = null

        fun add(e: RecorderEvent, fields: Map<String, String>) {
            when (e.kind) {
                EventKinds.VIDEO_START -> start = minOf(start ?: e.tMicros, e.tMicros)
                EventKinds.VIDEO_STOP -> stop = e.tMicros
                else -> fields[VideoEventText.MEDIA_US]?.toLongOrNull()?.let { media ->
                    val frame = fields[VideoEventText.FRAME_US]?.toLongOrNull()
                    placement.add(e.tMicros, media, frame)
                    lastSync = maxOf(lastSync ?: e.tMicros, e.tMicros)
                }
            }
        }

        fun clip(camera: String, file: String): VideoClip? =
            (placement.offset ?: start)?.let { VideoClip(camera, file, it, stop ?: lastSync) }
    }
}

/** Reports within this much of each other agree on a file's offset (a quarter of a 60 fps frame). */
private const val AgreeMicros = 4_000L

/** Reports that must agree before an offset counts as confirmed. */
private const val ConfirmedBy = 3

/** How long from a file's first sync its frame times place it. */
private const val PlacementWindowMicros = 60_000_000L

/**
 * The lowest of [offsets] that at least [ConfirmedBy] of them (itself included) agree with, within
 * [AgreeMicros]; the lowest one when none is confirmed; null when there are none. One or two stray
 * low reports are passed over, a steady lowest value is kept.
 */
fun confirmedLowest(offsets: List<Long>): Long? {
    val sorted = offsets.sorted()
    var hi = 0
    for (lo in sorted.indices) {
        while (hi < sorted.size && sorted[hi] - sorted[lo] <= AgreeMicros) hi++
        if (hi - lo >= ConfirmedBy) return sorted[lo]
    }
    return sorted.firstOrNull()
}

/**
 * Where a phone file's media time 0 is on the session clock, from its sync reports as they come (see
 * [VideoTimeline]): the [confirmedLowest] frame time less media time over the file's first minute, or
 * with no frame times, the lowest report time less media time. Used live (the clip index) and when a
 * session is read, so both place a file the same way.
 */
class PhonePlacement {
    private var firstSync: Long? = null
    private var lowestReport: Long? = null
    private val frameOffsets = ArrayList<Long>()

    /** The offset so far; null before the first sync. */
    var offset: Long? = null
        private set

    /** One sync at session time [atMicros]: [mediaMicros] of media, newest frame at [frameMicros]. Returns [offset]. */
    fun add(atMicros: Long, mediaMicros: Long, frameMicros: Long?): Long? {
        val first = firstSync ?: atMicros.also { firstSync = it }
        lowestReport = minOf(lowestReport ?: Long.MAX_VALUE, atMicros - mediaMicros)
        // A frame time after the report itself is not trusted.
        if (frameMicros != null && frameMicros <= atMicros && atMicros - first <= PlacementWindowMicros) {
            frameOffsets += frameMicros - mediaMicros
        }
        offset = confirmedLowest(frameOffsets) ?: lowestReport
        return offset
    }
}
