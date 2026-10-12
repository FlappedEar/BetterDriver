package com.flappedear.betterdriver.tracks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.coach.CoachReferenceStore
import com.flappedear.betterdriver.coach.CoachReferences
import com.flappedear.betterdriver.coach.coachPicks
import com.flappedear.betterdriver.coach.coachReferencesDir
import com.flappedear.betterdriver.feature.track.editor.EditorSession
import com.flappedear.betterdriver.feature.track.editor.TileLoader
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.circuitsDir
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.circuits.CircuitRefresh
import com.flappedear.betterdriver.timing.circuits.MaxCircuitListChars
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.detectTrack
import com.flappedear.betterdriver.update.HttpNetwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sessions the editor loads for one track, newest first: enough to check lines, bounded in memory. */
private const val MaxEditorSessions = 8

/** A last known position older than this does not name a new session: the phone may have moved since. */
private const val FreshPositionNanos = 10L * 60 * 1_000_000_000

/**
 * Where the phone is (UI-27) and what is there. [atNanos] is when the position was taken
 * ([SystemClock.elapsedRealtimeNanos]), or null when it is only where the newest session started.
 */
data class Here(val position: GeoPoint, val detection: TrackDetection, val atNanos: Long? = null) {
    /**
     * Whether the position is recent enough to name a session started now: a stale one could name a
     * road drive after last weekend's circuit. Checked at START, so a home screen left open goes stale.
     */
    fun fresh(nowNanos: Long = SystemClock.elapsedRealtimeNanos()) =
        atNanos != null && nowNanos - atNanos <= FreshPositionNanos
}

/**
 * A track opened in the editor, with the sessions recorded there. [stored] is the track as saved,
 * or null for one proposed from a session and not saved yet; [proposed] says the lines are a
 * proposal to check (FBBD-151).
 */
data class EditorData(
    val track: Track,
    val sessions: List<EditorSession>,
    val stored: Track? = track,
    val proposed: Boolean = false,
)

/** A track proposed from session [sessionId] (FBBD-151), opened in the editor before it is saved. */
private class Draft(val track: Track, val sessionId: String, val session: EditorSession)

/**
 * Saved tracks for the UI process (FBBD-54, FBBD-56). One per process; disk work runs on one
 * background thread and results come back as Compose state or callbacks on the main thread. The
 * recorder process reads the same directory when a session starts.
 */
@Suppress("TooManyFunctions") // One entry point per thing the tracks screens and the editor do.
class Tracks private constructor(private val context: Context) {
    // Made on the io thread when first used: making them reads the disk (FBBD-41).
    private val store by lazy { TrackStore(tracksDir(context)) }
    private val directory by lazy { CircuitDirectory(circuitsDir(context)) }
    private val sessions by lazy { SessionStore(sessionsDir(context)) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tracks-io") }
    private val main = Handler(Looper.getMainLooper())

    /** The track proposed from a session, until it is saved or another is proposed. io thread only. */
    private var draft: Draft? = null

    /** Each session's first position, by id, so sessions elsewhere are read once. io thread only. */
    private val sessionStarts = HashMap<String, GeoPoint?>()

    /** Map tiles for the editor, kept in app storage for use without signal (UI-26). */
    val tiles by lazy {
        TileLoader(
            File(context.filesDir, "tiles"),
            "BetterDriver/${BuildConfig.VERSION_NAME} (+https://github.com/FlappedEar/BetterDriver)",
        )
    }

    /** Saved tracks by name. */
    var list by mutableStateOf<List<Track>>(emptyList())
        private set

    /** Where the phone is, once [locate] has found it. */
    var here by mutableStateOf<Here?>(null)
        private set

    init {
        io.execute {
            directory.load()
            publish()
        }
    }

    fun refresh() = io.execute { publish() }

    /** Finds where the phone is: its last known position, else where the newest session started. */
    fun locate() = io.execute {
        val known = lastKnownPosition()
        val at = known?.first ?: newestSessionStart()
        val found = at?.let { Here(it, detectTrack(it, store.all(), directory.list), known?.second) }
        main.post { here = found }
    }

    /** The circuit list was checked for a newer copy on GitHub in this process. */
    private val circuitsChecked = AtomicBoolean(false)

    /**
     * Fetches a newer circuit list from the repo on GitHub, once per process (FBBD-79), on a thread of
     * its own so a slow network never holds up the tracks; with a newer list the place is found again.
     * Not while [recording]: nothing goes on the network then, so a download is also dropped when a
     * recording starts during it. Offline, the bundled or cached list stays.
     */
    fun refreshCircuits(recording: () -> Boolean) {
        if (recording() || !circuitsChecked.compareAndSet(false, true)) return
        // After the bundled and cached lists are loaded, so a fetched list is compared with them.
        io.execute {
            Thread({
                val network = HttpNetwork("BetterDriver/${BuildConfig.VERSION_NAME} (Android)")
                var dropped = false
                val result = directory.refresh { url ->
                    if (recording()) {
                        dropped = true
                        throw IOException("recording")
                    }
                    val body = ByteArrayOutputStream()
                    // Checked after each chunk read: a recording that starts closes the connection.
                    val r = network.download(url, body, MaxCircuitListChars.toLong()) {
                        !recording().also { dropped = dropped || it }
                    }
                    if (!r.ok) throw IOException("HTTP ${r.code}")
                    body.toByteArray().decodeToString()
                }
                // Dropped for a recording, not checked: the next visit to the tracks tries again.
                if (dropped) circuitsChecked.set(false)
                DebugLog.i(Tag, "circuit list: $result, revision ${directory.list.revision}")
                if (result == CircuitRefresh.Updated || result == CircuitRefresh.NotSaved) locate()
            }, "circuits-refresh").start()
        }
    }

    /** The circuit list, once loaded (an empty one before). */
    val circuits: CircuitList get() = directory.list

    /**
     * Stores [track] (new or edited) and hands back the stored copy; [onFailed] when it could not be
     * written. With [still], the track stored now is checked first, under the same hold as the write
     * (FBBD-156): if [still] says it is no longer the one the caller decided on (a restore or another
     * write replaced it), nothing is stored and [onChanged] runs on the main thread instead.
     */
    fun save(
        track: Track,
        onFailed: () -> Unit = {},
        still: (Track?) -> Boolean = { true },
        onChanged: () -> Unit = {},
        onSaved: (Track) -> Unit,
    ) = io.execute {
        var changed = false
        val stored = try {
            // Waits for a restore (FBBD-156).
            AppDataGate.write {
                if (still(store.get(track.id))) {
                    store.save(track)
                } else {
                    changed = true
                    null
                }
            }
        } catch (e: IOException) {
            DebugLog.w(Tag, "saving track ${track.id} failed", e)
            null
        }
        if (changed) {
            main.post { onChanged() }
            return@execute
        }
        if (stored != null && draft?.track?.id == track.id) draft = null
        publish()
        // New lines, or a track made from a session (FBBD-151): its sessions feed its coach reference (FBBD-168).
        if (stored != null) CoachReferences.refresh(context)
        main.post { if (stored != null) onSaved(stored) else onFailed() }
    }

    /**
     * Deletes the track with its coach reference and picked corners (FBBD-157); sessions recorded
     * there stay, with the track name they were recorded under. The coach files go first, so a track
     * re-created with the same id never picks up stale ones. [onDone] gets false when any is left.
     */
    fun delete(id: String, onDone: (Boolean) -> Unit) = io.execute {
        val references = CoachReferenceStore(coachReferencesDir(context))
        val picks = coachPicks(context)
        // Waits for a restore (FBBD-156), so the delete is not lost in its swap.
        val gone = AppDataGate.write {
            references.delete(id)
            try {
                picks.save(id, emptyList())
            } catch (e: IOException) {
                DebugLog.w(Tag, "deleting coach picks for $id failed", e)
            }
            val coachGone = references.get(id) == null && picks.get(id).isEmpty()
            if (coachGone) store.delete(id)
            coachGone && store.get(id) == null
        }
        if (gone && draft?.track?.id == id) draft = null
        if (!gone) DebugLog.w(Tag, "deleting track $id failed")
        publish()
        // Its sessions may now count for another track at the same place (FBBD-168).
        CoachReferences.refresh(context)
        main.post { onDone(gone) }
    }

    /**
     * Keeps [track], proposed from session [sessionId] driven as [session] (FBBD-151), for the editor
     * to open by its id; nothing is stored until the editor saves it. Call before navigating there.
     */
    fun propose(track: Track, sessionId: String, session: EditorSession) = io.execute {
        draft = Draft(track, sessionId, session)
    }

    /**
     * The track and the sessions recorded there, newest first; null if the track is gone. A proposed
     * track opens with its lines unsaved and its own session first, so its lap is the one shown.
     */
    fun openEditor(id: String, onReady: (EditorData?) -> Unit) = io.execute {
        val proposed = draft?.takeIf { it.track.id == id }
        val data = if (proposed != null) {
            val others = sessionsAt(proposed.track, skip = proposed.sessionId)
            val sessions = (listOf(proposed.session) + others).take(MaxEditorSessions)
            EditorData(proposed.track, sessions, stored = store.get(id), proposed = true)
        } else {
            store.get(id)?.let { EditorData(it, sessionsAt(it)) }
        }
        main.post { onReady(data) }
    }

    private fun publish() {
        val all = store.all()
        main.post { list = all }
    }

    private fun sessionsAt(track: Track, skip: String? = null): List<EditorSession> = sessions.list()
        .asSequence()
        .filter { it.state != SessionState.RECORDING && it.id != skip }
        .mapNotNull { s -> fixesIfAt(s.id, track)?.let { EditorSession(sessionTitle(s), it) } }
        .take(MaxEditorSessions)
        .toList()

    /** The session's positions if it started at [track]; a session known to start elsewhere is not read again. */
    private fun fixesIfAt(id: String, track: Track): List<Fix>? {
        fun at(p: GeoPoint?) = p != null && distanceMeters(p, track.center) <= track.radiusMeters
        if (id in sessionStarts && !at(sessionStarts[id])) return null
        val fixes = readFixes(id)
        val start = fixes?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
        sessionStarts[id] = start
        return fixes.takeIf { at(start) }
    }

    // A damaged session is skipped; the others still load.
    @Suppress("TooGenericExceptionCaught")
    private fun readFixes(id: String): List<Fix>? = try {
        sessions.read(id).sampleSets.mapNotNull { it.toFix() }.filter { it.lat != 0.0 || it.lon != 0.0 }
    } catch (e: Exception) {
        DebugLog.w(Tag, "reading session $id failed", e)
        null
    }

    private fun newestSessionStart(): GeoPoint? {
        val newest = sessions.list().firstOrNull { it.state != SessionState.RECORDING } ?: return null
        return sessionStarts.getOrPut(newest.id) {
            readFixes(newest.id)?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
        }
    }

    @SuppressLint("MissingPermission") // Checked first.
    /** The newest last known position and when it was taken ([SystemClock.elapsedRealtimeNanos]). */
    private fun lastKnownPosition(): Pair<GeoPoint, Long>? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val manager = context.getSystemService(LocationManager::class.java)
        if (!granted || manager == null) return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { GeoPoint(it.latitude, it.longitude) to it.elapsedRealtimeNanos }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val Tag = "Tracks"

        @Volatile
        private var instance: Tracks? = null

        fun get(context: Context): Tracks = instance ?: synchronized(this) {
            instance ?: Tracks(context.applicationContext).also { instance = it }
        }
    }
}
