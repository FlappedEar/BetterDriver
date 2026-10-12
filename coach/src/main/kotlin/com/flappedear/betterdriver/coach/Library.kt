package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import java.io.File
import java.io.IOException
import java.util.Properties

/** The saved track a session was recorded at, from its track event; null if none. */
fun SessionData.savedTrackId(): String? = TrackEvent.savedTrackId(events)

/** The session's fixes, in time order. */
fun SessionData.coachFixes(): Sequence<CoachFix> = sampleSets.asSequence().mapNotNull { it.toCoachFix() }

/**
 * Keeps each track's coach reference up to date with the recorded sessions (FBBD-26, FBBD-96).
 *
 * Which track each session was at is read once and remembered in [dir], so only new sessions are
 * opened. A track's reference is rebuilt from all of its sessions when a new one appears there or
 * its lines change. Run it off the main thread; it reads whole sessions.
 */
class CoachLibrary(private val sessions: SessionStore, private val dir: File) {
    val references = CoachReferenceStore(dir)
    private val index = File(dir, "sessions.properties")

    /**
     * The reference for [track], rebuilt first if sessions or lines changed since it was saved.
     * Sessions still recording, and [exclude], are left out.
     */
    @Synchronized
    fun reference(track: Track, exclude: String? = null): CoachReference? {
        val known = readIndex()
        val usable = sessions.list().filter { it.state != SessionState.RECORDING && it.id != exclude }
        var changed = false
        for (s in usable) {
            if (known.containsKey(s.id)) continue
            // Only the events name the track: samples are read just for the sessions at it.
            known[s.id] = readOrNull(s.dir, samples = false)?.savedTrackId().orEmpty()
            changed = true
        }
        if (changed) writeIndex(known)
        val here = usable.filter { known[it.id] == track.id }.map { it.id }
        val current = references.get(track.id)
        if (current != null && current.fits(track) && current.sessions.toSet() == here.toSet()) return current
        val laps = here.mapNotNull { id ->
            val data = readOrNull(sessions.dir(id)) ?: return@mapNotNull null
            SessionLaps(id, tracedLaps(track, data.coachFixes()))
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

    private fun readIndex(): MutableMap<String, String> {
        val p = Properties()
        try {
            if (index.isFile) index.inputStream().use(p::load)
        } catch (_: IOException) {
            p.clear()
        } catch (_: IllegalArgumentException) {
            p.clear()
        }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }.toMutableMap()
    }

    private fun writeIndex(map: Map<String, String>) {
        dir.mkdirs()
        val p = Properties()
        map.forEach { (k, v) -> p.setProperty(k, v) }
        val tmp = File(dir, index.name + ".tmp")
        tmp.outputStream().use { out ->
            p.store(out, "session id = saved track id (empty: none)")
            out.fd.sync()
        }
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }
}
