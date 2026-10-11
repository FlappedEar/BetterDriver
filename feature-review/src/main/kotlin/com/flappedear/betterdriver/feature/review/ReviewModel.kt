package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoPosition
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapTrace
import java.io.File

/** A lap by session and number; stable across reloads, so a choice survives a refresh. */
data class LapKey(val sessionId: String, val lapNumber: Int) {
    /** For saved state: "session:lap". */
    fun encode() = "$sessionId:$lapNumber"

    companion object {
        fun decode(text: String?): LapKey? {
            val i = text?.lastIndexOf(':') ?: return null
            val n = text.substring(i + 1).toIntOrNull()
            return if (i <= 0 || n == null) null else LapKey(text.substring(0, i), n)
        }
    }
}

/**
 * One camera's video of a session (FBBD-93): its clips on the session clock and the directory
 * holding their files. The phone camera is used when the session has it, as in the VBO export.
 */
class SessionVideo(val camera: String, clips: List<VideoClip>, val dir: File) {
    private val timeline = VideoTimeline(clips.filter { it.camera == camera })

    val clips: List<VideoClip> get() = timeline.clips

    /** The clip showing session time [sessionMicros], if any. */
    fun at(sessionMicros: Long): VideoPosition? = timeline.locate(camera, sessionMicros)

    fun file(clip: VideoClip) = File(dir, clip.file)

    /** Some of [startMicros, endMicros] is on video. */
    fun covers(startMicros: Long, endMicros: Long): Boolean = clips.any { c ->
        val end = c.endMicros ?: Long.MAX_VALUE
        c.startMicros < endMicros && end > startMicros
    }
}

/** A lap the review can show: its path and times, the session it is from, and that session's video. */
data class ReviewLap(val trace: LapTrace, val sessionTitle: String, val video: SessionVideo?) {
    val key: LapKey get() = LapKey(trace.sessionId, trace.lap.number)
    val number: Int get() = trace.lap.number
    val kind: LapKind get() = trace.lap.kind
    val timeMillis: Long get() = trace.lap.timeMillis

    /** The lap has video for at least part of it. */
    val hasVideo: Boolean get() = video?.covers(trace.lap.startMicros, trace.lap.endMicros) == true
}

/** One session's laps on a track. [bestNumber] is its fastest timed lap. */
data class ReviewSession(
    val id: String,
    val title: String,
    val detail: String,
    val laps: List<ReviewLap>,
    val bestNumber: Int?,
)

/** A saved track with the sessions recorded there, newest first. */
data class ReviewTrack(val id: String, val name: String, val sessions: List<ReviewSession>) {
    fun lap(key: LapKey): ReviewLap? =
        sessions.firstOrNull { it.id == key.sessionId }?.laps?.firstOrNull { it.number == key.lapNumber }

    /**
     * The pair shown first: the newest session's best lap against the fastest other timed lap on
     * the track (from any session), so a new outing is compared with the best so far. Null without
     * two timed laps.
     */
    fun defaultPair(): Pair<LapKey, LapKey>? {
        val timed = sessions.flatMap { s -> s.laps.filter { it.kind == LapKind.Timed } }
        // Sessions are newest first, so the first timed lap is from the newest session with one.
        val a = timed.firstOrNull()?.let { first -> timed.filter { it.trace.sessionId == first.trace.sessionId } }
            ?.minBy { it.timeMillis }
        val b = timed.filter { it.key != a?.key }.minByOrNull { it.timeMillis }
        return if (a == null || b == null) null else a.key to b.key
    }
}

/** What the review screen has to show. */
sealed interface ReviewLoad {
    /** Reading sessions: [done] of [total]. */
    data class Loading(val done: Int, val total: Int) : ReviewLoad

    /** Tracks with laps, most recently driven first. Empty when no session was recorded at a saved track. */
    data class Ready(val tracks: List<ReviewTrack>) : ReviewLoad
}
