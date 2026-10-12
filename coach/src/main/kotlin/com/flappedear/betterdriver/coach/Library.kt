package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.trackAlong
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** The saved track a session was recorded at, from its track event; null if none. */
fun SessionData.savedTrackId(): String? = TrackEvent.savedTrackId(events)

/** The session's fixes, in time order. */
fun SessionData.coachFixes(): Sequence<CoachFix> = sampleSets.asSequence().mapNotNull { it.toCoachFix() }

/**
 * Keeps each track's coach reference up to date with the recorded sessions (FBBD-26, FBBD-96, FBBD-168).
 *
 * A session counts for the track it recorded it was at. A session that recorded no saved track, or
 * one since deleted, counts for the track its positions reach, picked as the analysis and replay
 * pick it ([trackAlong]), when it has a timed lap there: a session driven before its track was made
 * from it (FBBD-151) feeds that track's reference.
 *
 * What is known about each session is kept in [dir] ([SessionPlaces]), so a session is opened again
 * only when it or the tracks around it change: the events once, for the track recorded, and the
 * samples only for a session that needs matching by position. [tracks] gives the saved tracks.
 *
 * Run it off the main thread; it reads whole sessions. The coach and the UI process can both use it:
 * a lock file in [dir] keeps them from working on the same files at once.
 */
class CoachLibrary(
    private val sessions: SessionStore,
    private val dir: File,
    private val tracks: () -> List<Track>,
) {
    val references = CoachReferenceStore(dir)
    private val places = SessionPlaces(File(dir, PlacesFile))

    /**
     * The reference for [track], rebuilt first if sessions or lines changed since it was saved.
     * Sessions still recording, and [exclude], are left out.
     */
    fun reference(track: Track, exclude: String? = null): CoachReference? = locked {
        val saved = tracks().filter { it.id != track.id } + track
        val found = match(saved) { false }
        build(track, found, exclude)
    }

    /**
     * Brings every coached track's reference up to date, so the next coach starts at once: after a
     * track is saved, edited or deleted, or sessions change. Stops early, keeping what it found,
     * when [stop] says so (it is asked between sessions).
     */
    fun refresh(stop: () -> Boolean = { false }) = locked {
        val saved = tracks()
        val found = match(saved, stop)
        if (found.complete) {
            for (t in saved.filter(::coaches)) {
                if (stop()) break
                build(t, found, exclude = null)
            }
        }
    }

    /** What [match] found: each usable session's track, and the laps it already traced on the way. */
    private class Found(
        val trackOf: Map<String, String>,
        val laps: Map<Pair<String, String>, List<TracedLap>>,
        val complete: Boolean,
    )

    /** Works out the track of every session that is not recording, from [places] and, where needed, the session. */
    private fun match(saved: List<Track>, stop: () -> Boolean): Found {
        val known = places.read()
        val listed = sessions.list()
        val ids = saved.map { it.id }.toSet()
        val timeable = saved.filter { it.timeable }
        val trackOf = HashMap<String, String>()
        val laps = HashMap<Pair<String, String>, List<TracedLap>>()
        var changed = known.keys.retainAll(listed.map { it.id }.toSet())
        var complete = true
        for (s in listed.filter { it.state != SessionState.RECORDING }) {
            if (stop()) {
                complete = false
                break
            }
            val stamp = stampOf(s)
            var place = known[s.id]?.takeIf { it.stamp == stamp }
                ?: SessionPlace(stamp, recorded = readOrNull(s.dir, samples = false)?.savedTrackId())
            val recorded = place.recorded
            if (recorded != null && recorded in ids) {
                trackOf[s.id] = recorded
            } else {
                place = locate(s, place, timeable, laps)
                place.matched?.let { trackOf[s.id] = it }
            }
            if (known[s.id] != place) {
                known[s.id] = place
                changed = true
            }
        }
        if (changed) places.write(known)
        return Found(trackOf, laps, complete)
    }

    /**
     * [place] with the track the session's positions reach among [timeable], read from the session
     * only when that cannot be known from its area. Laps traced here are kept in [laps].
     */
    private fun locate(
        s: SessionSummary,
        place: SessionPlace,
        timeable: List<Track>,
        laps: MutableMap<Pair<String, String>, List<TracedLap>>,
    ): SessionPlace {
        var data: SessionData? = null
        var p = place
        if (!p.read) {
            data = readOrNull(s.dir)
            if (data != null) p = p.copy(read = true, area = Area.of(data.coachFixes()), against = null, matched = null)
        }
        // Only tracks whose radius reaches the session's area can be reached by any of its positions.
        val near = p.area?.let { a -> timeable.filter { a.reaches(it) } }.orEmpty()
        val against = tracksKey(near)
        val settled = !p.read || p.against == against
        val d = if (settled || near.isEmpty()) null else data ?: readOrNull(s.dir)
        return when {
            settled -> p
            near.isEmpty() -> p.copy(against = against, matched = null)
            // An unreadable session is left as it was and tried again next time.
            d == null -> p
            else -> p.copy(against = against, matched = timedAt(s.id, d, near, laps))
        }
    }

    /**
     * The track among [near] that [data]'s positions reach first, if the session times a lap there;
     * a drive past the track does not count. Its laps go in [laps].
     */
    private fun timedAt(
        id: String,
        data: SessionData,
        near: List<Track>,
        laps: MutableMap<Pair<String, String>, List<TracedLap>>,
    ): String? {
        val positions = data.coachFixes().mapNotNull { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }
        val at = trackAlong(positions, near)
        val traced = at?.let { tracedLaps(it, data.coachFixes()) }.orEmpty()
        if (at == null || traced.none { it.lap.kind == LapKind.Timed }) return null
        laps[id to at.id] = traced
        return at.id
    }

    private fun build(track: Track, found: Found, exclude: String?): CoachReference? {
        val usable = sessions.list().filter { it.state != SessionState.RECORDING && it.id != exclude }
        val here = usable.filter { found.trackOf[it.id] == track.id }.map { it.id }
        val current = references.get(track.id)
        if (current != null && current.fits(track) && current.sessions.toSet() == here.toSet()) return current
        val laps = here.mapNotNull { id ->
            val traced = found.laps[id to track.id]
                ?: tracedLaps(track, (readOrNull(sessions.dir(id)) ?: return@mapNotNull null).coachFixes())
            SessionLaps(id, traced)
        }
        val built = buildReference(track, laps)
        if (built == null) references.delete(track.id) else references.save(built)
        return built
    }

    @Suppress("TooGenericExceptionCaught") // A damaged session is skipped, never a reason to stop coaching.
    private fun readOrNull(sessionDir: File, samples: Boolean = true): SessionData? = try {
        sessions.read(sessionDir, samples)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Runs [block] holding this process's lock and the lock file, so one process works on [dir] at a time. */
    private fun <T> locked(block: () -> T): T = synchronized(ProcessLock) {
        dir.mkdirs()
        FileChannel.open(File(dir, LockFile).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val lock = ch.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    private companion object {
        const val PlacesFile = "places.json"
        const val LockFile = "library.lock"

        /** One lock per process: a second lock on the same file from the same process would throw. */
        val ProcessLock = Any()

        /** A session changes when its state or length does (recovered, imported again). */
        fun stampOf(s: SessionSummary) = "${s.state} ${s.durationMicros ?: ""}"
    }
}
