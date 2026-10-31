package com.flappedear.betterdriver.analysis.demo

import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackStore

/** One thing the Demo track can drive: a session or built-in replay by [id], or the made-up circuit when null. */
data class DemoChoice(val id: String?, val label: String)

/** A session the Drives list can offer: its id, when it started, its track's name and whether it is still recording. */
data class DemoSession(val id: String, val startUtcMillis: Long, val track: String, val recording: Boolean)

/**
 * A replay built into the app: Arek's laps at one track, in the app's demo folder as [asset].replay and
 * [asset].track.json.
 */
data class BuiltInDrive(val id: String, val asset: String, val trackName: String, val startUtcMillis: Long)

/**
 * What the Demo track receiver drives (FBBD-233, FBBD-235), the same on Android and iOS (FBBD-373):
 * the made-up demo circuit, Arek's Silesia Ring and Jastrząb laps built into the app (FBBD-237,
 * FBBD-254), or a session on the phone replayed lap after lap. The choice is a session id in the small
 * file [ChoiceFile] in the app's files folder; no file means the demo circuit. The apps only read the
 * files: the choice, the Drives list and the fixes are made here, and logged here, tagged [Tag].
 */
object DemoTrack {
    const val Tag = "DemoReplay"

    /** The choice file's name in the app's files folder. */
    const val ChoiceFile = "demo_track_session"

    /** The choice of the Silesia Ring replay built into the app: Arek's 27 Sep sessions. */
    const val SilesiaRing = "built-in:silesia-ring"

    /** When Arek's built-in Silesia Ring session started (27 Sep 2026, 11:00:30 UTC), for its name. */
    const val SilesiaRingStartUtcMillis = 1_790_506_830_000L

    /** The choice of the Autodrom Jastrząb replay built into the app: Arek's 29 Aug sessions. */
    const val Jastrzab = "built-in:jastrzab"

    /** When Arek's first built-in Jastrząb session started (29 Aug 2026, 08:00:09 UTC), for its name. */
    const val JastrzabStartUtcMillis = 1_787_990_409_000L

    /** The made-up circuit's choice, first in the list. */
    val Circuit = DemoChoice(null, "Demo circuit (made up)")

    /** The replays built into the app, in the Drives list's order. */
    val builtIns = listOf(
        BuiltInDrive(SilesiaRing, "silesia-ring", "Silesia Ring", SilesiaRingStartUtcMillis),
        BuiltInDrive(Jastrzab, "jastrzab", "Autodrom Jastrząb", JastrzabStartUtcMillis),
    )

    /** The choice in the file in [filesDir], or null for the demo circuit. */
    fun chosen(filesDir: String): String? {
        val path = CoreFiles.join(filesDir, ChoiceFile)
        return if (CoreFiles.isFile(path)) CoreFiles.readText(path).trim().ifBlank { null } else null
    }

    /** Chooses [id] to drive, or the demo circuit when null. */
    fun choose(filesDir: String, id: String?) {
        val path = CoreFiles.join(filesDir, ChoiceFile)
        if (id == null) CoreFiles.delete(path) else CoreFiles.writeTextAtomically(path, id)
        logLine(CoreLog.LevelInfo, Tag, "demo track drives ${id ?: "the demo circuit"}")
    }

    /**
     * The Drives list: the demo circuit, the built-in replays and every session that is not recording,
     * newest first, each named by [label] of its start and its track. Logged with the count, so a log
     * tells an empty list from a missing session (Arek, 0.3.35).
     */
    fun choices(sessions: List<DemoSession>, label: (Long) -> String): List<DemoChoice> {
        val drives = sessions
            .filter { !it.recording }
            .sortedByDescending { it.startUtcMillis }
            .map { s ->
                val name = label(s.startUtcMillis)
                DemoChoice(s.id, if (s.track.isBlank()) name else "$name · ${s.track}")
            }
        logLine(CoreLog.LevelInfo, Tag, "drives list: ${drives.size} of ${sessions.size} sessions on the phone")
        val builtIn = builtIns.map { DemoChoice(it.id, "${label(it.startUtcMillis)} · ${it.trackName}") }
        return listOf(Circuit) + builtIn + drives
    }

    /** The choice [id] in [choices], the demo circuit when it is not there (a session deleted since). */
    fun current(choices: List<DemoChoice>, id: String?): DemoChoice = choices.firstOrNull { it.id == id } ?: Circuit

    /**
     * The fixes for a session starting now: choice [id]'s replay at its saved track, or the demo
     * circuit (its track saved first) when none is chosen or it cannot be replayed. [asset] reads a
     * file of the app's demo folder, [session] reads a session by id. Their UTC starts at [now], read
     * once the session is loaded, so a long read does not put it behind the recording's own start.
     */
    fun script(
        id: String?,
        tracks: TrackStore,
        asset: (String) -> String,
        session: (String) -> SessionData,
        now: () -> Long,
    ): GnssScript {
        val builtIn = builtIns.firstOrNull { it.id == id }
        val replay = when {
            id == null -> null
            builtIn != null -> builtIn(builtIn, tracks, asset, now)
            else -> session(id, tracks, session, now)
        }
        return replay ?: demoCircuit(tracks, now)
    }

    private fun demoCircuit(tracks: TrackStore, now: () -> Long): GnssScript {
        // A track already saved under its id is kept: Arek may have moved its lines.
        if (tracks.get(DemoCircuit.TrackId) == null) tracks.save(DemoCircuit.track)
        return DemoCircuit(now())
    }

    // A session that cannot be read must not stop the recording: the demo circuit drives instead.
    @Suppress("TooGenericExceptionCaught")
    private fun session(id: String, tracks: TrackStore, read: (String) -> SessionData, now: () -> Long): GnssScript? =
        try {
            val data = read(id)
            val track = trackFor(data, tracks.all())
            SessionReplay.of(data.sampleSets, track, now()).also {
                if (it != null) {
                    logLine(CoreLog.LevelInfo, Tag, "replaying session $id at ${track?.id ?: "no saved track"}")
                } else {
                    val why = "has no two timed laps at a saved track"
                    logLine(CoreLog.LevelWarn, Tag, "session $id $why: demo circuit instead")
                }
            }
        } catch (e: Exception) {
            logLine(CoreLog.LevelWarn, Tag, "session $id cannot be replayed: demo circuit instead", e)
            null
        }

    /**
     * Arek's laps at one track built into the app, joined into one loop on the computer
     * (BuiltInReplayTest), with the track they were timed at, saved first as the demo circuit's is.
     */
    @Suppress("TooGenericExceptionCaught") // As for a session: the demo circuit drives instead.
    private fun builtIn(
        drive: BuiltInDrive,
        tracks: TrackStore,
        asset: (String) -> String,
        now: () -> Long,
    ): GnssScript? =
        try {
            val track = TrackJson.decode(asset("${drive.asset}.track.json"))
            val replay = SessionReplay.read(asset("${drive.asset}.replay"), now())
            // Arek's edits are kept while they time the laps. A line flipped or removed would not: only the
            // built-in start/finish comes back, his sectors, pit lines and name stay (Codex).
            val saved = tracks.get(track.id)
            if (saved == null) {
                tracks.save(track)
            } else if (!replay.timesLaps(saved)) {
                val mended = saved.copy(
                    kind = track.kind,
                    startFinish = track.startFinish,
                    finish = track.finish,
                    center = track.center,
                    radiusMeters = track.radiusMeters,
                )
                tracks.save(if (replay.timesLaps(mended)) mended else track)
            }
            logLine(CoreLog.LevelInfo, Tag, "replaying the built-in ${track.name} laps at ${track.id}")
            replay
        } catch (e: Exception) {
            logLine(CoreLog.LevelWarn, Tag, "built-in ${drive.asset} replay cannot be read: demo circuit instead", e)
            null
        }
}
