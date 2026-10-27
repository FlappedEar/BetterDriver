package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.feature.review.TraceComparison
import com.flappedear.betterdriver.feature.review.VideoTarget
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoPosition
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.replay.LapTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Two laps with their videos for the iPad review (FBBD-331), as Android's lap review (FBBD-93):
// the same clips, the same placing on the session clock, the same TraceComparison.

/**
 * One camera's video of a session: its clips on the session clock and the folder holding their
 * files ([dir]), as Android's SessionVideo. The phone camera is used when the session has it.
 */
class LapVideoSource(val camera: String, clips: List<VideoClip>, val dir: String) {
    private val timeline = VideoTimeline(clips.filter { it.camera == camera })

    val clips: List<VideoClip> get() = timeline.clips

    /** The clip showing session time [sessionMicros], if any. */
    fun at(sessionMicros: Long): VideoPosition? = timeline.locate(camera, sessionMicros)

    fun path(clip: VideoClip): String = "$dir/${clip.file}"

    /** Some of [startMicros, endMicros] is on video. */
    fun covers(startMicros: Long, endMicros: Long): Boolean = clips.any { c ->
        val end = c.endMicros ?: Long.MAX_VALUE
        c.startMicros < endMicros && end > startMicros
    }

    companion object {
        /**
         * The video of [timeline]'s clips whose files are [present] in [dir]: the phone camera when
         * it is there, else the first camera; null without any (Android's sessionVideo).
         */
        fun of(timeline: VideoTimeline, dir: String, present: (String) -> Boolean): LapVideoSource? {
            val here = timeline.clips.filter { present("$dir/${it.file}") }
            val camera = here.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: here.firstOrNull()?.camera
            return camera?.let { LapVideoSource(it, here, dir) }
        }
    }
}

/** A lap of the review with its session's video. */
class VideoLap(val trace: LapTrace, val video: LapVideoSource?) {
    val number: Int get() = trace.lap.number
    val timeMillis: Long get() = trace.lap.timeMillis

    /** The lap has video for at least part of it. */
    val hasVideo: Boolean get() = video?.covers(trace.lap.startMicros, trace.lap.endMicros) == true

    /** The video frame at [lapMicros] since the lap start; null where the lap has none. */
    fun videoAt(lapMicros: Long): VideoTarget? {
        val pos = video?.at(trace.lap.startMicros + lapMicros) ?: return null
        return if (pos.mediaMicros < 0) null else VideoTarget(pos.clip, pos.mediaMicros / MicrosPerMilli)
    }

    /** The file of [target]. */
    fun path(target: VideoTarget): String = checkNotNull(video).path(target.clip)

    /** Speed in km/h at [lapMicros]; null without one. */
    fun speedAt(lapMicros: Long): Double? = trace.at(lapMicros).speedKmh
}

/** Lap [b] against lap [a] on video, as Android's Comparison. */
class LapVideoReview(val a: VideoLap, val b: VideoLap) {
    val traces = TraceComparison(a.trace, b.trace)
}

/**
 * The clips of a session's clip index (`<session>/video/clips.json`, Android's VideoIndex) on the
 * recorder's terms, as Android's indexedClips: clips still on a camera are left out. An index that
 * does not read gives no clips, which only leaves the video out.
 */
fun indexedClips(json: String): List<VideoClip> = try {
    Json.parseToJsonElement(json).jsonObject["clips"]?.jsonArray.orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        if ((o.text("status") ?: "LOCAL") == "LOCAL") clipOf(o) else null
    }
} catch (_: IllegalArgumentException) {
    emptyList()
}

/** An index entry on the recorder's terms, as Android's recorderClip; null when it lacks a field. */
private fun clipOf(o: JsonObject): VideoClip? {
    val file = o.text("fileName")
    val source = o.text("sourceId")
    val offset = o["sessionOffsetUs"]?.jsonPrimitive?.longOrNull
    return if (file != null && source != null && offset != null) {
        VideoClip(source, file, offset, o["durationUs"]?.jsonPrimitive?.longOrNull?.let { offset + it })
    } else {
        null
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private const val MicrosPerMilli = 1_000L
