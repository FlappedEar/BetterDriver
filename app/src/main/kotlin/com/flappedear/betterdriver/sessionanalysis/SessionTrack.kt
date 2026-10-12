package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.trackAlong

/** The saved track id a session's "track" event names ("saved <id> rev <n> <name>"), or null. */
internal fun savedTrackId(events: List<RecorderEvent>): String? {
    val words = events.firstOrNull { it.kind == "track" }?.text?.split(' ') ?: return null
    return words.getOrNull(1)?.takeIf { words.first() == "saved" }
}

/**
 * The track whose start/finish line [data] is analysed against: the saved track the session
 * recorded when it started, else the nearest saved track with a line whose radius reaches the
 * session's first position, else the first such track the session drove into (it started away
 * from the track, or the track was made from it afterwards, FBBD-151). The track's current lines
 * are used, so a line set after the session still works.
 */
@Suppress("ReturnCount")
internal fun trackFor(data: SessionData, saved: List<Track>): Track? {
    val withLine = saved.filter { it.startFinish != null }
    savedTrackId(data.events)?.let { id -> withLine.firstOrNull { it.id == id }?.let { return it } }
    return trackAlong(positions(data), withLine)
}

/** The session's positions in time order; fixes a receiver reports without a position are left out. */
internal fun positions(data: SessionData): Sequence<GeoPoint> = sessionFixes(data).asSequence()
    .mapNotNull { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }

/** The session's fixes in time order. */
internal fun sessionFixes(data: SessionData): List<Fix> =
    data.sampleSets.sortedBy { it.tMicros }.mapNotNull { it.toFix() }
