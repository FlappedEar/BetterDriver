package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.trackAlong

/** The saved track id a session's "track" event names ("saved <id> rev <n> <name>"), or null. */
fun savedTrackId(events: List<RecorderEvent>): String? {
    val words = events.firstOrNull { it.kind == "track" }?.text?.split(' ') ?: return null
    return words.getOrNull(1)?.takeIf { words.first() == "saved" }
}

/**
 * The track whose start/finish line [data] is analysed against: the saved track the session
 * recorded when it started, else the nearest saved track with a line whose radius reaches the
 * session's first position, else the first such track the session drove into (it started away
 * from the track, or the track was made from it afterwards, FBBD-151). The track's current lines
 * are used, so a line set after the session still works. Android and iOS pick it the same way.
 */
@Suppress("ReturnCount")
fun trackFor(data: SessionData, saved: List<Track>): Track? {
    val withLine = saved.filter { it.timeable }
    savedTrackId(data.events)?.let { id -> withLine.firstOrNull { it.id == id }?.let { return it } }
    return trackAlong(sessionPositions(data), withLine)
}

/** The session's positions in time order; fixes a receiver reports without a position are left out. */
fun sessionPositions(data: SessionData): Sequence<GeoPoint> = sessionFixes(data).asSequence()
    .mapNotNull { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }

/** The session's fixes in time order. */
fun sessionFixes(data: SessionData): List<Fix> =
    data.sampleSets.sortedBy { it.tMicros }.mapNotNull { it.toFix() }

/** One timed lap as the review lists it. */
data class ReviewLap(
    val number: Int,
    val seconds: Double,
    /** Behind the fastest lap, in seconds; 0 for the fastest and for laps that do not count. */
    val deltaSeconds: Double,
    val fastest: Boolean,
    /** Whether it counts for best, theoretical best and the line (an out lap or a cut does not). */
    val counts: Boolean,
)

/** What the iPad review shows of one session (FBBD-331), worked out by the shared core as on Android. */
data class SessionReview(
    val track: Track?,
    val laps: List<ReviewLap>,
    val bestLapSeconds: Double?,
    val theoreticalBestSeconds: Double?,
)

/** Reviews [data] at the track it was driven on, among [tracks] (see [trackFor]). */
fun reviewOf(data: SessionData, tracks: List<Track>): SessionReview {
    val track = trackFor(data, tracks) ?: return SessionReview(null, emptyList(), null, null)
    val analysis = analyzeSession(telemetrySessionOf(data, track), identity = track.identity)
    val fastest = analysis.laps.fastestLapIndex
    val laps = analysis.laps.timedLaps.mapIndexed { i, lap ->
        ReviewLap(lap.number, lap.durationSeconds, lap.deltaToBestSeconds, i == fastest, lap.referenceEligible)
    }
    return SessionReview(
        track,
        laps,
        fastest?.let { analysis.laps.timedLaps[it].durationSeconds },
        analysis.theoreticalBest?.totalSeconds,
    )
}
