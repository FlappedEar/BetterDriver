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

    /** Id of the saved track the session was timed with, or null when it had none. */
    fun savedTrackId(events: List<RecorderEvent>): String? = events
        .lastOrNull { it.kind == KIND }
        ?.text
        ?.split(' ')
        ?.takeIf { it.size >= 2 && it[0] == "saved" }
        ?.get(1)
}
