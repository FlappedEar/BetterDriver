package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.coach.LastTimeHere
import com.flappedear.betterdriver.coach.PinnedSession
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.time.Instant
import java.time.ZoneId
import com.flappedear.betterdriver.coach.lastTimeHere as sharedLastTimeHere

// Drive's Pinned cards (UI-308 A) are worked out by the shared core (coach DrivePinned.kt, FBBD-421),
// as on iOS; the app only hands it the listed sessions with the day each started on here.

/** [sessions] as the shared core reads them, each with the day it started on in [zone]. */
internal fun pinnedSessions(sessions: List<SessionSummary>, zone: ZoneId = ZoneId.systemDefault()) = sessions.map {
    PinnedSession(
        id = it.id,
        track = it.info.track,
        startUtcMillis = it.info.startUtcMillis,
        epochDay = Instant.ofEpochMilli(it.info.startUtcMillis).atZone(zone).toLocalDate().toEpochDay(),
        recording = it.state == SessionState.RECORDING,
    )
}

/** The last day at [track] among [sessions] (newest first), from the shared core. */
internal fun lastTimeHere(
    sessions: List<SessionSummary>,
    facts: Map<String, SessionFacts>,
    track: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): LastTimeHere? = sharedLastTimeHere(pinnedSessions(sessions, zone), facts, track)
