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
 * Two laps another screen opens the review on (FBBD-153): [a] against [b], with the cursor at
 * [atFraction] of lap A's distance (as at a corner), or at the start line when null.
 */
data class ReviewStart(val a: LapKey, val b: LapKey, val atFraction: Double? = null) {
    /** [track] has both laps. */
    fun on(track: ReviewTrack): Boolean = track.lap(a) != null && track.lap(b) != null

    /** Where the cursor starts when [a] and [b] are shown: [atFraction] for the laps asked for. */
    fun atFor(a: LapKey, b: LapKey): Double? = atFraction.takeIf { a == this.a && b == this.b }
}

/** The track the review opens on: the one with both laps of [start], else the most recently driven. */
fun firstTrack(tracks: List<ReviewTrack>, start: ReviewStart?): ReviewTrack =
    tracks.firstOrNull { start?.on(it) == true } ?: tracks.first()

/**
 * The pair [track] opens on: the laps of [start] when it has both. Else none: the review opens on
 * the lap picker with nothing picked, as Arek asked (2026-10-12), never on an earlier comparison.
 */
fun firstPair(track: ReviewTrack, start: ReviewStart?): Pair<LapKey, LapKey>? =
    if (start != null && start.on(track)) start.a to start.b else null

/**
 * What the review shows: the picker with laps [a] and [b] picked (encoded [LapKey]s, null when not)
 * and [active] taking the next tapped lap, or, when [picking] is false, those two laps compared.
 */
internal data class Picks(val a: String?, val b: String?, val active: Slot, val picking: Boolean) {
    /** [lap] into the active slot; the other slot is next. */
    fun pick(lap: LapKey): Picks =
        if (active == Slot.A) copy(a = lap.encode(), active = Slot.B) else copy(b = lap.encode(), active = Slot.A)

    /** Leaving a comparison (back or Change laps): the picker again, with nothing picked. */
    fun leave(): Picks = cleared()

    companion object {
        /** The picker with nothing picked. */
        fun cleared() = Picks(null, null, Slot.A, picking = true)

        /** How the review opens on a track: on [pair] compared, else on the picker with nothing picked. */
        fun opening(pair: Pair<LapKey, LapKey>?): Picks =
            pair?.let { Picks(it.first.encode(), it.second.encode(), Slot.A, picking = false) } ?: cleared()
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
}

/** What the review screen has to show. */
sealed interface ReviewLoad {
    /** Reading sessions: [done] of [total]. */
    data class Loading(val done: Int, val total: Int) : ReviewLoad

    /** Tracks with laps, most recently driven first. Empty when no session was recorded at a saved track. */
    data class Ready(val tracks: List<ReviewTrack>) : ReviewLoad
}
