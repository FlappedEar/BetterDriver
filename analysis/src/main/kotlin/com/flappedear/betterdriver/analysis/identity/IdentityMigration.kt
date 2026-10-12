package com.flappedear.betterdriver.analysis.identity

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.trackAlong
import java.io.File
import java.io.IOException

/** Sessions tried for one track's identity before it is left for the analysis to make. */
const val MaxIdentitySessions = 5

/**
 * The corner identity of [track] (FBBD-172) made from [data], a session recorded there, or null when
 * the session has no lap that splits into corners and straights. Nothing is written.
 */
fun identityFrom(track: Track, data: SessionData): TrackRevision? =
    analyzeSession(telemetrySessionOf(data, track), identity = track.identity).identity

/**
 * Gives every stored track that can time laps and has no corner identity yet one (FBBD-172), from the
 * newest of its sessions whose fastest lap splits into corners and straights: the sessions it
 * recorded it was at, then those whose positions reach it ([trackAlong]). Tracks keep their lines,
 * revision and update time; sessions are only read, never written. At most [MaxIdentitySessions]
 * sessions are tried per track; a track with none left gets its identity when it is first analysed.
 * [stop] is asked between sessions. Returns the ids of the tracks given one.
 */
fun migrateTrackIdentities(
    tracks: TrackStore,
    sessions: SessionStore,
    stop: () -> Boolean = { false },
): List<String> {
    val saved = tracks.all()
    val missing = saved.filter { it.identity == null && it.timeable }.associateBy { it.id }.toMutableMap()
    val tried = HashMap<String, Int>()
    val done = mutableListOf<String>()
    val listed = sessions.list()
        .filter { it.state != SessionState.RECORDING }
        .sortedByDescending { it.info.startUtcMillis }
    for (summary in listed.asSequence().takeWhile { missing.isNotEmpty() && !stop() }) {
        val (track, data) = sessionAt(sessions, summary.dir, saved, missing) ?: continue
        val identity = track.takeIf { tried.merge(it.id, 1, Int::plus)!! <= MaxIdentitySessions }
            ?.let { identityFrom(it, data) }
        val stored = identity?.let { tracks.updateIdentity(track.id, null, it) }
        if (stored?.identity != null) {
            missing.remove(track.id)
            if (stored.identity == identity) done += track.id
        }
    }
    return done
}

/**
 * The track among [missing] the session in [dir] was driven at, with the session; null for a session
 * at another track, at none, or that cannot be read. A session that names another saved track counts
 * for that one only.
 */
private fun sessionAt(
    sessions: SessionStore,
    dir: File,
    saved: List<Track>,
    missing: Map<String, Track>,
): Pair<Track, SessionData>? {
    val named = readOrNull(sessions, dir, samples = false)?.let { TrackEvent.savedTrackId(it.events) }
    val elsewhere = named != null && named !in missing && saved.any { it.id == named }
    val data = if (elsewhere) null else readOrNull(sessions, dir, samples = true)
    val track = data?.let { named?.let(missing::get) ?: trackAlong(positions(it), saved)?.let { t -> missing[t.id] } }
    return track?.let { it to data }
}

private fun positions(data: SessionData): Sequence<GeoPoint> = data.sampleSets.sortedBy { it.tMicros }
    .asSequence()
    .mapNotNull { it.toFix() }
    .mapNotNull { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }

/** A session that cannot be read is skipped, never a reason to stop. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun readOrNull(sessions: SessionStore, dir: File, samples: Boolean): SessionData? = try {
    sessions.read(dir, samples)
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
}
