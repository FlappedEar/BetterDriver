@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.detectTrack
import com.flappedear.betterdriver.timing.track.newTrackAt
import com.flappedear.betterdriver.timing.track.newTrackFrom
import com.flappedear.betterdriver.timing.track.storedOver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** Sessions the editor loads for one track, newest first: enough to check lines, bounded in memory (as Android). */
private const val MaxEditorSessions = 8

/** Newer sessions than this many are not looked at for the editor. */
private const val MaxScannedSessions = 40

private const val MillisPerSecond = 1000.0

/** Closer than this, the place is "here" on the Tracks screen. */
private const val HereMeters = 100.0
private const val MetersPerKm = 1000.0

/** What is where the phone is, for the Tracks screen's "Where you are" (UI-27). */
class Here(
    /** The saved track the phone is at, if any. */
    val track: Track?,
    /** Else the circuit from the list near the phone, if any. */
    val circuit: Circuit?,
    val distanceMeters: Double,
    val lat: Double,
    val lon: Double,
) {
    /** "here", or "1.2 km away". */
    val away: String
        get() = if (distanceMeters < HereMeters) "here" else "${fixed(distanceMeters / MetersPerKm, 1)} km away"
}

/**
 * The saved tracks on the iPhone and iPad (FBBD-336), one TrackJson file each in [tracksDir]
 * (Application Support/tracks/<id>.json), which the recorder (LiveSession) and session import read:
 * the iOS side of Android's Tracks and TrackStore. A save replaces the file atomically with the next
 * revision, so the next session's first fix times laps with it. Calls hold one lock, so the Tracks
 * screen and the editor never interleave a read and a write; a call may read the disk, so the views
 * call it off the main thread where it can take long (the editor's sessions).
 */
@Suppress("TooManyFunctions") // One entry point per thing the Tracks screen and the editor do.
class TrackLibrary(private val tracksDir: String, private val sessionsDir: String) {
    private val lock = NSRecursiveLock()

    /** Every readable track, by name. */
    fun list(): List<Track> = locked { TrackFiles.read(tracksDir) }

    /** The saved track [id], or null. */
    fun get(id: String): Track? = locked { read(id) }

    /**
     * Stores [track] (new or edited) with the next revision and now as its update time, keeping
     * the stored corner identity, as TrackStore.save on Android; returns the stored copy. Any
     * failure is a [TrackSaveException]: another Kotlin exception would end the app from Swift.
     */
    @Throws(TrackSaveException::class)
    fun save(track: Track): Track = saving(track.id) {
        locked {
            if (!validId(track.id)) throw TrackSaveException("bad track id: ${track.id}")
            val stored = track.storedOver(read(track.id), nowMillis())
            NSFileManager.defaultManager.createDirectoryAtPath(tracksDir, true, null, null)
            // Foundation writes a temporary file and renames it over the old one.
            val ok = NSString.create(string = TrackJson.encode(stored))
                .writeToFile(path(track.id), atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!ok) throw TrackSaveException("cannot write track ${track.id}")
            stored
        }
    }

    /**
     * Stores [identity] as the corner identity of track [id] (FBBD-172) when the stored one is still
     * [expected], as TrackStore.updateIdentity on Android, so two analyses never replace each other's;
     * the lines, the revision and the update time stay. Returns the track as stored afterwards, null
     * if there is none or it cannot be written.
     */
    fun updateIdentity(id: String, expected: TrackRevision?, identity: TrackRevision): Track? = locked {
        val current = read(id)
        if (current == null || current.identity != expected || current.identity == identity) return@locked current
        val updated = current.copy(identity = identity)
        val ok = NSString.create(string = TrackJson.encode(updated))
            .writeToFile(path(id), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (ok) updated else null
    }

    /** A new track named after the circuit [circuit], saved with no lines yet (Create track). */
    @Throws(TrackSaveException::class)
    fun createFrom(circuit: Circuit): Track = save(saving(circuit.name) { newTrackFrom(circuit) })

    /** A new track at [lat], [lon] named [name], saved with no lines yet (New track here). */
    @Throws(TrackSaveException::class)
    fun createAt(lat: Double, lon: Double, name: String): Track =
        save(saving(name) { newTrackAt(GeoPoint(lat, lon), name.trim()) })

    /** Whether [name], trimmed, can name a track. */
    fun validName(name: String): Boolean = name.trim().let { it.isNotEmpty() && it.length <= Track.MaxNameChars }

    /** Deletes track [id]; sessions recorded there keep their data and track name. True when it is gone. */
    fun delete(id: String): Boolean = locked {
        if (!validId(id)) return@locked false
        val p = path(id)
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(p)) fm.removeItemAtPath(p, null)
        !fm.fileExistsAtPath(p)
    }

    /** What is at [lat], [lon]: a saved track, else a circuit from [circuits], else nothing. */
    fun here(lat: Double, lon: Double, circuits: CircuitList): Here {
        val at = GeoPoint(lat, lon)
        return when (val d = detectTrack(at, list(), circuits)) {
            is TrackDetection.Saved -> Here(d.track, null, d.distanceMeters, lat, lon)
            is TrackDetection.Directory -> Here(null, d.circuit, d.distanceMeters, lat, lon)
            TrackDetection.Unknown -> Here(null, null, 0.0, lat, lon)
        }
    }

    /**
     * The sessions recorded at [track], newest first, with their positions (as Android's
     * Tracks.sessionsAt): those that start within the track's radius. A session that cannot be read
     * is skipped.
     */
    @Suppress("TooGenericExceptionCaught") // A damaged session is skipped; the others still load.
    fun editorSessions(track: Track): List<EditorSession> {
        val store = SessionStore(sessionsDir)
        val all = try {
            store.list()
        } catch (_: Exception) {
            return emptyList() // the editor opens without a lap rather than ending the app
        }
        return all.asSequence()
            .filter { it.state != SessionState.RECORDING }
            .sortedByDescending { it.info.startUtcMillis }
            .take(MaxScannedSessions)
            .mapNotNull { s ->
                val fixes = try {
                    store.read(s.id).sampleSets.mapNotNull { it.toFix() }
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                fixes.takeIf { startsAt(it, track) }?.let { EditorSession(title(s.info.startUtcMillis), it) }
            }
            .take(MaxEditorSessions)
            .toList()
    }

    private fun startsAt(fixes: List<Fix>, track: Track): Boolean {
        val first = fixes.firstOrNull() ?: return false
        return distanceMeters(GeoPoint(first.lat, first.lon), track.center) <= track.radiusMeters
    }

    @Suppress("TooGenericExceptionCaught") // Any damaged file reads as no track, never as a crash.
    private fun read(id: String): Track? {
        val text = if (validId(id)) NSString.stringWithContentsOfFile(path(id), NSUTF8StringEncoding, null) else null
        return try {
            text?.let(TrackJson::decode)?.takeIf { it.id == id }
        } catch (_: Exception) {
            null
        }
    }

    /** Runs [body], turning any failure into a [TrackSaveException] about [what]. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> saving(what: String, body: () -> T): T = try {
        body()
    } catch (e: TrackSaveException) {
        throw e
    } catch (e: Exception) {
        throw TrackSaveException("cannot save $what: ${e.message}", e)
    }

    private fun path(id: String): String {
        require(validId(id)) { "bad track id: $id" }
        return "$tracksDir/$id.json"
    }

    private fun validId(id: String) = id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_" }

    private inline fun <T> locked(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }
}

/** A track could not be written. */
class TrackSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()

/** "29 Aug 2026 at 17:20" in the device's language, as the session list. */
private fun title(startUtcMillis: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterShortStyle
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(startUtcMillis / MillisPerSecond))
