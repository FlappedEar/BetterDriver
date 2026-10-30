package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.findSessionLoop
import com.flappedear.betterdriver.timing.track.proposeTrack

// A track made from a session at no saved track (FBBD-151), for the iPad and iPhone (FBBD-367) as
// Android's analysis offers it: when the session drove a closed loop again and again, the line goes
// across the middle of the longest straight and the driver checks it in the track editor before it
// is saved, as Garmin Catalyst's "Add a Track".

/**
 * The track to make from a session: [track] (a saved track here without a line gets it, else a new
 * one), the session's [fixes] titled [label] for the editor, and what the summary says of it.
 */
class TrackProposal(val track: Track, val fixes: List<Fix>, val label: String, val text: String)

/** The button that opens the proposed track in the editor. */
const val MakeTrackAction = "Make a track from this session"

private const val MetersPerKm = 1000.0

/**
 * The track to make from session [data], titled [label] (its date and time), with the [saved]
 * tracks and the circuit [directory]; null when it is at a saved track with a line or drove no loop.
 */
fun trackProposal(data: SessionData, saved: List<Track>, directory: CircuitList, label: String): TrackProposal? {
    val fixes = sessionFixes(data)
    val loop = fixes.takeIf { trackFor(data, saved) == null }?.let(::findSessionLoop) ?: return null
    val fallback = data.info.track.ifBlank { "Track of $label" }.take(Track.MaxNameChars)
    val track = proposeTrack(loop, saved, directory, fallback)
    val at = if (track.name == fallback) "" else " at ${track.displayName}"
    val laps = if (loop.laps == 1) "1 lap" else "${loop.laps} laps"
    val text = "No laps yet: this session drove $laps of a ${fixed(loop.lengthMeters / MetersPerKm, 2)} km " +
        "loop$at that has no start/finish line. Make a track from it: the line goes across the middle of " +
        "the longest straight, and you check it in the track editor before it is saved."
    return TrackProposal(track, fixes, label, text)
}
