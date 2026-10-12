package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.IOException

/**
 * [session] analysed with [track]'s corner identity (FBBD-172). When the analysis made one, the
 * track's first or the next revision after its shape changed, it is kept with the track, so the
 * next analysis, the coach and the picks name the corners alike. When another analysis kept one
 * first, the session is analysed again with that one.
 */
internal fun analyzeAtTrack(store: TrackStore, track: Track, session: TelemetrySession): SessionAnalysis {
    val analysis = analyzeSession(session, identity = track.identity)
    val made = analysis.identity?.takeIf { it != track.identity }
    val kept = made?.let { keepIdentity(store, track, it) }
    return if (kept == null || kept == made) analysis else analyzeSession(session, identity = kept)
}

/** Stores [identity] for [track] unless another was stored since it was read; the identity stored after. */
private fun keepIdentity(store: TrackStore, track: Track, identity: TrackRevision): TrackRevision? = try {
    // Waits for a restore (FBBD-156), so nothing is written into a half-swapped folder.
    AppDataGate.write { store.updateIdentity(track.id, track.identity, identity) }?.identity
} catch (e: IOException) {
    DebugLog.w("SessionAnalysis", "corner identity of ${track.id} not saved", e)
    null
}
