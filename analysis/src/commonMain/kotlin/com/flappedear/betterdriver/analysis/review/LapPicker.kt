package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track

// Compare laps from any sessions at a track (FBBD-92) for the iPad and iPhone, as Android's lap
// picker (LapPicker.kt, ReviewLaps.kt): sessions at each saved track, newest first, with their laps;
// tap a slot, then a lap, the other slot is next; Compare opens the two in the lap review.

/** A lap of a saved session the picker offers: its path and times, its session's title and video. */
class PickerLap(val trace: LapTrace, val sessionTitle: String, private val video: LapVideoSource?) {
    /** "session:lap", as Android's LapKey encodes it. */
    val key: String get() = "${trace.sessionId}:${trace.lap.number}"
    val number: Int get() = trace.lap.number
    val timed: Boolean get() = trace.lap.kind == LapKind.Timed
    val timeText: String get() = formatLapTime(trace.lap.timeMillis)

    /** The lap has video for at least part of it. */
    val hasVideo: Boolean get() = video?.covers(trace.lap.startMicros, trace.lap.endMicros) == true

    /** What Android's row says after the time: "Out lap", "Best", "Video", joined. */
    fun notes(best: Boolean): String = listOfNotNull(
        when (trace.lap.kind) {
            LapKind.Out -> "Out lap"
            LapKind.In -> "In lap"
            LapKind.Timed -> null
        },
        if (best) "Best" else null,
        if (hasVideo) "Video" else null,
    ).joinToString(" · ")

    /** The lap with its session's video, for the lap review. */
    fun onVideo(): VideoLap = VideoLap(trace, video)
}

/** One session's laps at a track; [bestNumber] is its fastest timed lap. */
class PickerSession(
    val id: String,
    val title: String,
    val detail: String,
    val laps: List<PickerLap>,
    val bestNumber: Int?,
)

/** A saved track with the sessions recorded there that have laps, newest first. */
class PickerTrack(val id: String, val name: String, val sessions: List<PickerSession>) {
    fun lap(key: String): PickerLap? = sessions.firstNotNullOfOrNull { s -> s.laps.firstOrNull { it.key == key } }
}

/**
 * Session [data]'s laps at [track] for the picker, titled [title] (the session's date and time) with
 * its video [video]; null when it has no lap there.
 */
fun pickerSession(data: SessionData, track: Track, title: String, video: LapVideoSource?): PickerSession? {
    val laps = replayLaps(data.id, data.sampleSets, track)
    if (laps.laps.isEmpty()) return null
    val timed = laps.laps.count { it.lap.kind == LapKind.Timed }
    val best = laps.best?.lap
    val detail = listOfNotNull(
        if (timed == 1) "1 lap" else "$timed laps",
        best?.let { "best ${formatLapTime(it.timeMillis)}" },
    ).joinToString(" · ")
    return PickerSession(data.id, title, detail, laps.laps.map { PickerLap(it, title, video) }, best?.number)
}

/**
 * [sessions] (each with the id of the saved track it is at, newest first) grouped by track, the
 * most recently driven track first, as Android's review lists them; a track not among [saved] is
 * left out.
 */
fun pickerTracks(sessions: List<Pair<String, PickerSession>>, saved: List<Track>): List<PickerTrack> =
    sessions.groupBy({ it.first }, { it.second }).mapNotNull { (id, list) ->
        saved.firstOrNull { it.id == id }?.let { PickerTrack(id, it.displayName, list) }
    }

/**
 * The two laps picked, by key, and which slot the next tapped lap goes into ([nextIsA]), as
 * Android's Picks: a lap goes into the next slot and the other slot is next.
 */
data class LapPicks(val a: String? = null, val b: String? = null, val nextIsA: Boolean = true) {
    fun pick(key: String): LapPicks = if (nextIsA) copy(a = key, nextIsA = false) else copy(b = key, nextIsA = true)

    /** The slot tapped takes the next lap. */
    fun next(isA: Boolean): LapPicks = copy(nextIsA = isA)

    /** Two different laps are picked. */
    val ready: Boolean get() = a != null && b != null && a != b

    /** "A" or "B" when [key] is picked, else null. */
    fun slotOf(key: String): String? = when (key) {
        a -> "A"
        b -> "B"
        else -> null
    }
}

/** Laps [a] against [b] of [tracks] on video, as Android's lap review opens them; null without both. */
fun pickedReview(tracks: List<PickerTrack>, a: String, b: String): LapVideoReview? {
    val lapA = tracks.firstNotNullOfOrNull { it.lap(a) }
    val lapB = tracks.firstNotNullOfOrNull { it.lap(b) }
    return if (lapA != null && lapB != null) LapVideoReview(lapA.onVideo(), lapB.onVideo()) else null
}
