package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.recorder.RecorderEvent

/**
 * The session's record of where it was, logged once when the track is picked (FBBD-55):
 * "saved <id> rev <n> <name>", "circuit <id> <name>" or "unknown". A session bundle uses it to
 * carry the track the session was timed with (FBBD-80).
 */
object TrackEvent {
    const val KIND = "track"

    fun text(found: TrackDetection): String = when (found) {
        is TrackDetection.Saved -> "saved ${found.track.id} rev ${found.track.revision} ${found.track.displayName}"
        is TrackDetection.Directory -> "circuit ${found.circuit.id} ${found.circuit.name}"
        TrackDetection.Unknown -> "unknown"
    }

    /**
     * Live timing placed a provisional start/finish (FBBD-335); text "line <lat> <lon> <lat> <lon>
     * at <saved track id or provisional> <name>", the line's left then right end.
     */
    const val PROVISIONAL_KIND = "track_provisional"

    fun provisionalText(track: Track): String {
        val line = checkNotNull(track.startFinish) { "a provisional track has its line" }
        val ends = listOf(line.left.lat, line.left.lon, line.right.lat, line.right.lon).joinToString(" ")
        return "line $ends at ${track.id} ${track.displayName}"
    }

    /** Id of the saved track the session was timed with, or null when it had none. */
    fun savedTrackId(events: List<RecorderEvent>): String? = events
        .lastOrNull { it.kind == KIND }
        ?.text
        ?.split(' ')
        ?.takeIf { it.size >= 2 && it[0] == "saved" }
        ?.get(1)
}
