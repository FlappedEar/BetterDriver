package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFiles
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

private const val MicrosPerMilli = 1_000L

/**
 * When [s] ended: from its length, or for a session not yet recovered (no length), from its last
 * sample. Never from its file times: a session copied in (import, restore) has the copy's times.
 */
private fun endedUtcMillis(s: SessionSummary): Long {
    val length = s.durationMicros ?: lastSampleMicros(s.dir) ?: 0L
    return s.info.startUtcMillis + length / MicrosPerMilli
}

/** The time of the last sample in [dir]'s chunks, scanned one chunk at a time so no samples are kept. */
@Suppress("TooGenericExceptionCaught") // A damaged session counts as ending at its start.
private fun lastSampleMicros(dir: File): Long? = try {
    var last: Long? = null
    for (c in SessionFiles.chunks(dir)) {
        Frames.scan(c.readBytes()) { rec ->
            if (rec is Record.Samples) last = maxOf(last ?: Long.MIN_VALUE, rec.set.tMicros)
        }
    }
    last
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
}

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
 * samples only for a session that needs matching by position. [tracks] gives the saved tracks, and
 * [plans] each track's line plan targets (FBBD-307), which the reference aims at.
 *
 * Run it off the main thread; it reads whole sessions. The coach and the UI process can both use it:
 * a lock file in [dir] keeps them from working on the same files at once.
 */
class CoachLibrary(
    private val sessions: SessionStore,
    private val dir: File,
    private val plans: (trackId: String) -> PlannedTargets? = { null },
    private val tracks: () -> List<Track>,
) {
    val references = CoachReferenceStore(dir)
    private val places = SessionPlaces(File(dir, PlacesFile))

    /**
     * The reference for [track], rebuilt first if sessions, lines or the line plan changed since it was saved.
     * Sessions still recording, and [exclude], are left out.
     */
    fun reference(track: Track, exclude: String? = null): CoachReference? = locked {
        val saved = tracks().filter { it.id != track.id } + track
        val found = match(saved) { false }
        build(track, found, exclude)
    }

    /**
     * Whether a session [reference] was built from ended within [SameDayMillis] before [nowUtcMillis]:
     * the driver is back out on the same day (FBBD-190).
     */
    fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long): Boolean {
        val ids = reference.sessions.toSet()
        val ended = sessions.list().filter { it.id in ids }.maxOfOrNull(::endedUtcMillis)
        return ended != null && nowUtcMillis - ended in 0..SameDayMillis
    }

    /**
     * Brings every coached track's reference up to date, so the next coach starts at once: after a
     * track is saved, edited or deleted, or sessions change. Stops early, keeping the matches it
     * found but no half-built reference, when [stop] says so (it is asked between sessions). The
     * references of tracks deleted meanwhile are removed, so one written back by a build that raced
     * the delete never outlives it.
     */
    fun refresh(stop: () -> Boolean = { false }) = locked {
        val saved = tracks()
        val found = match(saved, stop)
        if (found.complete) {
            for (t in saved.filter(::coaches)) {
                if (stop()) break
                build(t, found, exclude = null, stop)
            }
        }
        val ids = tracks().map { it.id }.toSet()
        references.trackIds().filter { it !in ids }.forEach { references.delete(it) }
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

    /**
     * The reference for [track] from the sessions [found] there, rebuilt and saved if they or the
     * lines changed. When [stop] says so between sessions, nothing is saved and the reference as it
     * was is returned.
     */
    private fun build(track: Track, found: Found, exclude: String?, stop: () -> Boolean = { false }): CoachReference? {
        val usable = sessions.list().filter { it.state != SessionState.RECORDING && it.id != exclude }
        val here = usable.filter { found.trackOf[it.id] == track.id }.map { it.id }
        val current = references.get(track.id)
        val plan = plans(track.id)
        val upToDate = current != null && current.fits(track) && current.sessions.toSet() == here.toSet() &&
            current.planKey == plan?.key
        val laps = if (upToDate) null else lapsAt(track, here, found, stop)
        if (laps == null) return current
        val built = buildReference(track, laps, plan)
        if (built == null) references.delete(track.id) else references.save(built)
        return built
    }

    /** The laps at [track] of the sessions [here]; null if [stop] said so before all were read. */
    private fun lapsAt(track: Track, here: List<String>, found: Found, stop: () -> Boolean): List<SessionLaps>? {
        val laps = ArrayList<SessionLaps>()
        for (id in here) {
            val traced = found.laps[id to track.id]
            if (traced == null && stop()) return null
            val read = traced ?: readOrNull(sessions.dir(id))?.let { tracedLaps(track, it.coachFixes()) }
            read?.let { laps += SessionLaps(id, it) }
        }
        return laps
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

        /** A session ended at most this long ago counts as the same day: 12 hours. */
        const val SameDayMillis = 12L * 60 * 60 * 1_000

        /** One lock per process: a second lock on the same file from the same process would throw. */
        val ProcessLock = Any()

        /** A session changes when its state or length does (recovered, imported again). */
        fun stampOf(s: SessionSummary) = "${s.state} ${s.durationMicros ?: ""}"
    }
}
