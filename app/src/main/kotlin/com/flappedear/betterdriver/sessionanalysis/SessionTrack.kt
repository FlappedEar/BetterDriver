package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.review.savedTrackId as commonSavedTrackId
import com.flappedear.betterdriver.analysis.review.sessionFixes as commonSessionFixes
import com.flappedear.betterdriver.analysis.review.sessionPositions
import com.flappedear.betterdriver.analysis.review.trackFor as commonTrackFor
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.track.Track

// The track a session is analysed against, shared with the iOS app (FBBD-331): see analysis.review.

/** The saved track id a session's "track" event names ("saved <id> rev <n> <name>"), or null. */
internal fun savedTrackId(events: List<RecorderEvent>): String? = commonSavedTrackId(events)

/** The track whose start/finish line [data] is analysed against (analysis.review.trackFor). */
internal fun trackFor(data: SessionData, saved: List<Track>): Track? = commonTrackFor(data, saved)

/** The session's positions in time order; fixes a receiver reports without a position are left out. */
internal fun positions(data: SessionData): Sequence<GeoPoint> = sessionPositions(data)

/** The session's fixes in time order. */
internal fun sessionFixes(data: SessionData): List<Fix> = commonSessionFixes(data)
