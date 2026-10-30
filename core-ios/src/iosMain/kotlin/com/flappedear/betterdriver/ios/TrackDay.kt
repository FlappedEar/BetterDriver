package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.identity.analyzeKeepingIdentity
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeDay
import com.flappedear.betterdriver.analysis.line.dayPlan
import com.flappedear.betterdriver.analysis.line.dayProof
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.feature.analysis.TrackDayState
import com.flappedear.betterdriver.feature.analysis.trackDayUi
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

/** One session of the day as read: its laps and when it started. */
private class DaySessionRead(val line: LineSession, val startUtcMillis: Long, val track: Track)

/**
 * The track day page (FBBD-315) on the iPhone and iPad (FBBD-331), as Android's TrackDayRoute reads
 * it: sessions [ids] at one track, read together on the day's fastest lap's corners, kept in [plans]
 * as the track's day plan for the next day there (its coach and its proof), and measured against the
 * plan of the day before at the track. Corners are named with the track's corner identity, as the
 * review names them, and an identity the day's analysis makes is kept with the track.
 */
// A session that cannot be read is left out of the day, whatever the reason.
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal fun trackDayState(
    store: SessionStore,
    saved: List<Track>,
    plans: DayPlanStore,
    tracks: TrackLibrary,
    ids: List<String>,
): TrackDayState {
    val read = ids.mapNotNull { id ->
        try {
            val data = store.read(id)
            val track = trackFor(data, saved) ?: return@mapNotNull null
            val session = telemetrySessionOf(data, track)
            val start = data.info.startUtcMillis
            DaySessionRead(LineSession(session, deriveSourceLapSession(session), time(start), id), start, track)
        } catch (_: Exception) {
            null
        }
    }.sortedBy { it.startUtcMillis }
    // The day's track is the one most of its sessions were driven at.
    val track = read.groupBy { it.track.id }.maxByOrNull { it.value.size }?.value?.first()?.track
        ?: return TrackDayState.Unavailable(NoDayText)
    val day = read.filter { it.track.id == track.id }
    val starts = day.associate { it.line.id to it.startUtcMillis }
    // With the track's corner identity (FBBD-172), kept with the track as Android's analyzeAtTrack
    // keeps it, so the day plan names corners as the next day's analysis and the coach do.
    val analyze = { s: LineSession ->
        analyzeKeepingIdentity(s.session, track.identity, starts.getValue(s.id)) { expected, made ->
            tracks.updateIdentity(track.id, expected, made)?.identity
        }
    }
    val analysis = analyzeDay(day.map { it.line }, analyze) ?: return TrackDayState.Unavailable(NoDayText)
    val first = day.first().startUtcMillis
    val date = format(first, "yyyy-MM-dd", posix = true)
    val last = day.last()
    val plan = dayPlan(analysis, track.id, date, last.line.id, last.startUtcMillis)
    val previous = plans.before(track.id, date)
    // A day plan that cannot be saved only leaves the next day there without it.
    try {
        plans.save(plan)
    } catch (_: Exception) {
    }
    val proof = previous?.let { dayProof(it, analysis) }
    val previousLabel = previous?.let { dayLabel(it.day) }
    return TrackDayState.Ready(trackDayUi(analysis, track.displayName, format(first, DayPattern), proof, previousLabel))
}

/** "Fri 29 Aug" for an ISO [day], at noon so no time zone moves it. */
private fun dayLabel(day: String): String {
    val parsed = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        dateFormat = "yyyy-MM-dd HH"
    }.dateFromString("$day 12") ?: return day
    return NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        dateFormat = DayPattern
    }.stringFromDate(parsed)
}

private fun time(utcMillis: Long) = format(utcMillis, "HH:mm")

private fun format(utcMillis: Long, pattern: String, posix: Boolean = false): String = NSDateFormatter().apply {
    locale = if (posix) NSLocale(localeIdentifier = "en_US_POSIX") else NSLocale.currentLocale
    dateFormat = pattern
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(utcMillis / MillisPerSecond))

private const val DayPattern = "EEE d MMM"
private const val MillisPerSecond = 1000.0

private const val NoDayText =
    "No track day for these sessions: it needs two sessions at one saved track with a lap through its corners each."
