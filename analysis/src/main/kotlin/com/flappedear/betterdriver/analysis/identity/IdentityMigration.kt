package com.flappedear.betterdriver.analysis.identity

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
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
 * Whether [this] track needs a corner identity made: it can time laps and has none, or its identity is
 * of the other topology (the track was changed between a circuit and a point-to-point course since).
 */
val Track.needsIdentity: Boolean
    get() = timeable && identity.let { it == null || it.closed == pointToPoint }

/**
 * Gives every stored track that [needsIdentity] one (FBBD-172), from the
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
    val missing = saved.filter { it.needsIdentity }.associateBy { it.id }.toMutableMap()
    val tried = HashMap<String, Int>()
    val done = mutableListOf<String>()
    val listed = sessions.list()
        .filter { it.state != SessionState.RECORDING }
        .sortedByDescending { it.info.startUtcMillis }
    for (summary in listed.asSequence().takeWhile { missing.isNotEmpty() && !stop() }) {
        val (track, data) = sessionAt(sessions, summary.dir, saved, missing) ?: continue
        val identity = track.takeIf { tried.merge(it.id, 1, Int::plus)!! <= MaxIdentitySessions }
            ?.let { identityFrom(it, data) }
        val stored = identity?.let { tracks.updateIdentity(track.id, track.identity, it) }
        if (stored != null && !stored.needsIdentity) {
            missing.remove(track.id)
            if (stored.identity == identity) done += track.id
        }
    }
    return done
}

/**
 * Brings the identity of every stored track that has one up to date with the newest of [newSessions]
 * recorded there (FBBD-172): when its fastest lap no longer drives the identity's shape (a chicane was
 * added), the track gets the next revision, as the lap analysis would make it when the session is
 * opened. One session per track, the newest; [stop] is asked between sessions. Returns the ids of the
 * tracks given a new revision.
 */
fun reviseTrackIdentities(
    tracks: TrackStore,
    sessions: SessionStore,
    newSessions: List<SessionSummary>,
    stop: () -> Boolean = { false },
): List<String> {
    val saved = tracks.all()
    val unchecked = saved.filter { it.identity != null && !it.needsIdentity }.associateBy { it.id }.toMutableMap()
    val done = mutableListOf<String>()
    val listed = newSessions.filter { it.state != SessionState.RECORDING }.sortedByDescending { it.info.startUtcMillis }
    for (summary in listed.asSequence().takeWhile { unchecked.isNotEmpty() && !stop() }) {
        val (track, data) = sessionAt(sessions, summary.dir, saved, unchecked) ?: continue
        unchecked.remove(track.id)
        val made = identityFrom(track, data)?.takeIf { it != track.identity }
        if (made != null && tracks.updateIdentity(track.id, track.identity, made)?.identity == made) done += track.id
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

/**
 * Runs [migrateTrackIdentities] and [reviseTrackIdentities] again only when what they look at has
 * changed (FBBD-172): the tracks that need an identity (by id and revision) or the finished sessions.
 * The sessions finished since the last completed run (all of them at the first) are checked against
 * the identities of their tracks, so a changed layout gets its revision without opening the session.
 * A run that was stopped is not counted, so the next one picks it up. For the app's background
 * refresh, which runs after every track or session change: a new, imported or restored track gets its
 * identity without a restart, and a refresh with nothing new costs two listings.
 */
class IdentityMigrations {
    private var lastKey: Any? = null
    private var checkedSessions: Set<String> = emptySet()

    @Synchronized
    fun runIfChanged(tracks: TrackStore, sessions: SessionStore, stop: () -> Boolean = { false }): List<String> {
        val key = migrationKey(tracks, sessions)
        if (key == lastKey) return emptyList()
        val finished = sessions.list().filter { it.state != SessionState.RECORDING }
        val made = migrateTrackIdentities(tracks, sessions, stop)
        val revised = reviseTrackIdentities(tracks, sessions, finished.filter { it.id !in checkedSessions }, stop)
        // Keyed by what is there after the run, so the identities it stored do not count as a change.
        if (stop()) {
            lastKey = null
        } else {
            lastKey = migrationKey(tracks, sessions)
            checkedSessions = finished.map { it.id }.toSet()
        }
        return (made + revised).distinct()
    }

    private fun migrationKey(tracks: TrackStore, sessions: SessionStore): Any {
        val needing = tracks.all().filter { it.needsIdentity }.map { it.id to it.revision }.toSet()
        val finished = sessions.list().filter { it.state != SessionState.RECORDING }.map { it.id }.toSet()
        return needing to finished
    }
}
