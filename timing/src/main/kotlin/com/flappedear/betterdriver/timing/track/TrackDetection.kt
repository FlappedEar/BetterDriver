package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import java.util.Locale
import java.util.UUID

/** What [detectTrack] found where the session starts (FBBD-55). */
sealed interface TrackDetection {
    /** One of Arek's tracks; timing starts with its lines. */
    data class Saved(val track: Track, val distanceMeters: Double) : TrackDetection

    /** No saved track is near, but a directory circuit is: offer to create a track from it after the outing. */
    data class Directory(val circuit: Circuit, val distanceMeters: Double) : TrackDetection

    /** Nothing known is near: offer to create a track at this position after the outing. */
    data object Unknown : TrackDetection
}

/**
 * Picks the track for a session from the car's first good position (FBBD-55). A saved track whose
 * radius reaches [at] wins, the nearest if several do; otherwise the nearest directory circuit
 * names the place. Call it once at session start: the result is fixed for the outing and never
 * prompts while driving; the offer to create a track belongs on the screens after the session.
 */
fun detectTrack(at: GeoPoint, saved: List<Track>, directory: CircuitList): TrackDetection {
    val track = saved
        .map { it to distanceMeters(at, it.center) }
        .filter { (t, d) -> d <= t.radiusMeters }
        .minByOrNull { it.second }
    if (track != null) return TrackDetection.Saved(track.first, track.second)
    return directory.find(at)?.let { TrackDetection.Directory(it, distanceMeters(at, it.position)) }
        ?: TrackDetection.Unknown
}

/**
 * The saved track with a start/finish line that a session's [positions] (in time order) reach
 * first: the nearest one whose radius reaches the first position, else the first one a later
 * position comes within. A session started at home and driven to the track, or one at a place whose
 * track was made from it afterwards (FBBD-151), still gets its laps.
 */
fun trackAlong(positions: Sequence<GeoPoint>, saved: List<Track>): Track? {
    val withLine = saved.filter { it.timeable }
    if (withLine.isEmpty()) return null
    return positions.firstNotNullOfOrNull { at ->
        withLine
            .map { it to distanceMeters(at, it.center) }
            .filter { (t, d) -> d <= t.radiusMeters }
            .minByOrNull { it.second }
            ?.first
    }
}

/** A new track named and placed from a directory circuit, with no lines yet (FBBD-79, FBBD-56). */
fun newTrackFrom(circuit: Circuit, id: String = newTrackId()): Track = Track(
    id = id,
    name = circuit.name,
    center = circuit.position,
    country = circuit.country,
    radiusMeters = circuit.radiusMeters,
    circuitId = circuit.id,
)

/** A new track at a place the directory does not know. */
fun newTrackAt(position: GeoPoint, name: String, id: String = newTrackId()): Track =
    Track(id = id, name = name, center = position)

fun newTrackId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)
