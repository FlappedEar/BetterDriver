package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track

/** The saved track id a session's "track" event names ("saved <id> rev <n> <name>"), or null. */
internal fun savedTrackId(events: List<RecorderEvent>): String? {
    val words = events.firstOrNull { it.kind == "track" }?.text?.split(' ') ?: return null
    return words.getOrNull(1)?.takeIf { words.first() == "saved" }
}

/**
 * The track whose start/finish line [data] is analysed against: the saved track the session
 * recorded when it started, else the nearest saved track with a line whose radius reaches the
 * session's first position. The track's current lines are used, so a line set after the session
 * still works.
 */
@Suppress("ReturnCount")
internal fun trackFor(data: SessionData, saved: List<Track>): Track? {
    val withLine = saved.filter { it.startFinish != null }
    savedTrackId(data.events)?.let { id -> withLine.firstOrNull { it.id == id }?.let { return it } }
    val first = data.sampleSets.sortedBy { it.tMicros }.asSequence()
        .mapNotNull { it.toFix() }
        .firstOrNull { it.lat != 0.0 || it.lon != 0.0 }
        ?.let { GeoPoint(it.lat, it.lon) } ?: return null
    return withLine
        .map { it to distanceMeters(first, it.center) }
        .filter { (track, d) -> d <= track.radiusMeters }
        .minByOrNull { it.second }
        ?.first
}
