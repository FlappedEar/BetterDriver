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
import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.detectTrack
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

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

/** A track opened in the editor, with the sessions recorded there. */
data class EditorData(val track: Track, val sessions: List<EditorSession>)

/**
 * Saved tracks for the UI process (FBBD-54, FBBD-56). One per process; disk work runs on one
 * background thread and results come back as Compose state or callbacks on the main thread. The
 * recorder process reads the same directory when a session starts.
 */
class Tracks private constructor(private val context: Context) {
    private val store = TrackStore(tracksDir(context))
    private val directory = CircuitDirectory(circuitsDir(context))
    private val sessions = SessionStore(sessionsDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tracks-io") }
    private val main = Handler(Looper.getMainLooper())

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

    /** Stores [track] (new or edited) and hands back the stored copy; [onFailed] when it could not be written. */
    fun save(track: Track, onFailed: () -> Unit = {}, onSaved: (Track) -> Unit) = io.execute {
        val stored = try {
            store.save(track)
        } catch (e: IOException) {
            DebugLog.w(Tag, "saving track ${track.id} failed", e)
            null
        }
        publish()
        main.post { if (stored != null) onSaved(stored) else onFailed() }
    }

    /**
     * Deletes the track and its coach reference (FBBD-157); sessions recorded there stay, with the
     * track name they were recorded under. The reference goes first, so a track re-created with the
     * same id never picks up a stale one. [onDone] gets false when either is still there.
     */
    fun delete(id: String, onDone: (Boolean) -> Unit) = io.execute {
        val references = CoachReferenceStore(coachReferencesDir(context))
        references.delete(id)
        val referenceGone = references.get(id) == null
        if (referenceGone) store.delete(id)
        val gone = referenceGone && store.get(id) == null
        if (!gone) DebugLog.w(Tag, "deleting track $id failed")
        publish()
        main.post { onDone(gone) }
    }

    /** The track and the sessions recorded there, newest first; null if the track is gone. */
    fun openEditor(id: String, onReady: (EditorData?) -> Unit) = io.execute {
        val data = store.get(id)?.let { EditorData(it, sessionsAt(it)) }
        main.post { onReady(data) }
    }

    private fun publish() {
        val all = store.all()
        main.post { list = all }
    }

    private fun sessionsAt(track: Track): List<EditorSession> = sessions.list()
        .asSequence()
        .filter { it.state != SessionState.RECORDING }
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
