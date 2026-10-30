package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.filesEndingWith
import com.flappedear.betterdriver.io.withFolderLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Arek's tracks, one JSON file each in [dir] (FBBD-54). Writes go to a temporary file that is
 * synced and then renamed over the old one, so a crash or power loss leaves either the old or the
 * new track, never half of one. A file that cannot be read is moved aside, never overwritten or
 * deleted, so a damaged file or one from a newer app keeps its lines.
 *
 * Safe to use from several instances, threads and processes (Audit A02): the app makes one wherever it
 * needs tracks, and the editor, the session analysis and the coach references all write. Each change
 * (read, check, write or delete) holds a monitor per folder and a file lock next to the folder, so no
 * change is lost and two writes never share a temporary file. Reads need no lock: a file is only ever
 * replaced whole. Blocking; call it off the main thread. Shared by the Android app and the iOS app
 * (FBBD-359).
 */
@Suppress("TooManyFunctions") // One entry point per thing kept: tracks, their corners and their corrections.
class TrackStore(private val dir: String) {

    /** Every readable track, by name. Unreadable files are moved aside and reported in [setAside]. */
    fun all(): List<Track> {
        val files = CoreFiles.filesEndingWith(dir, Suffix).map { CoreFiles.join(dir, it) }
        return files.mapNotNull(::readOrSetAside).sortedBy { it.displayName.lowercase() }
    }

    fun get(id: String): Track? {
        val file = fileFor(id)
        return if (CoreFiles.isFile(file)) readOrSetAside(file) else null
    }

    /**
     * Saves [track], replacing any track with the same id. The stored copy has the next revision
     * and [nowUtcMillis] as its update time; it is returned. The stored corner identity (FBBD-172) is
     * kept: only [updateIdentity] changes it, so an editor or an import holding an older one, or none,
     * never renames the corners here. A new track keeps the identity it comes with.
     *
     * With [adoptIdentity], for a track received from the other device with the driver's corner
     * corrections (FBBD-179), its identity replaces the one here as the next revision after it
     * ([storedOver]).
     */
    fun save(
        track: Track,
        nowUtcMillis: Long = nowMillis(),
        adoptIdentity: Boolean = false,
    ): Track = locked {
        val stored = track.storedOver(get(track.id), nowUtcMillis, adoptIdentity)
        writeAtomically(fileFor(track.id), TrackJson.encode(stored))
        stored
    }

    /**
     * Stores [identity] as the corner identity of track [id] (FBBD-172) when the stored one is still
     * [expected], so two analyses never replace each other's; the lines, the [Track.revision] and the
     * update time stay as they are. Returns the track as stored afterwards, null if there is none.
     */
    fun updateIdentity(id: String, expected: TrackRevision?, identity: TrackRevision): Track? = locked {
        val current = get(id)
        if (current == null || current.identity != expected || current.identity == identity) {
            current
        } else {
            current.copy(identity = identity).also { writeAtomically(fileFor(id), TrackJson.encode(it)) }
        }
    }

    /**
     * Applies the driver's correction [edit] to the corner identity of track [id] (FBBD-179) when the
     * stored one is still [expected], so a correction is never made to corners the driver did not see.
     * The update time becomes [nowUtcMillis], so a backup merge keeps the corrected track; the lines and
     * [Track.revision] stay. Returns the track as stored afterwards (unchanged when the identity moved
     * on, or the edit does not apply to it), null if there is none.
     */
    fun correctIdentity(
        id: String,
        expected: TrackRevision,
        edit: SegmentEdit,
        nowUtcMillis: Long = nowMillis(),
    ): Track? = locked {
        val current = get(id)
        val made = current?.identity?.takeIf { it == expected }?.corrected(edit)
        if (current == null || made == null || made == current.identity) {
            current
        } else {
            current.copy(identity = made, updatedUtcMillis = maxOf(nowUtcMillis, current.updatedUtcMillis))
                .also { writeAtomically(fileFor(id), TrackJson.encode(it)) }
        }
    }

    /** Deletes the track; false when there was none. */
    fun delete(id: String): Boolean {
        val file = fileFor(id)
        return CoreFiles.isDirectory(dir) && locked { CoreFiles.delete(file) }
    }

    /** Files moved aside because they could not be read. */
    fun setAside(): List<String> = CoreFiles.list(dir).filter { UnreadableMarker in it }.map { CoreFiles.join(dir, it) }

    private fun fileFor(id: String): String {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_" }) { "bad track id: $id" }
        return CoreFiles.join(dir, id + Suffix)
    }

    /**
     * A file that cannot be read is set aside under the lock, and only if it still cannot be read
     * then: a writer may have replaced it with a good track since this read (Audit A02).
     */
    private fun readOrSetAside(file: String): Track? = read(file) ?: locked {
        read(file).also { track ->
            if (track == null && CoreFiles.exists(file)) CoreFiles.move(file, file + UnreadableMarker + nowMillis())
        }
    }

    private fun read(file: String): Track? = try {
        TrackJson.decode(CoreFiles.readText(file)).takeIf { CoreFiles.join(dir, it.id + Suffix) == file }
            ?: throw TrackFormatException("id does not match file name")
    } catch (_: TrackFormatException) {
        null
    } catch (_: IOException) {
        null
    }

    /** Runs [change] holding this folder's lock, for this process's threads and other processes. */
    private fun <T> locked(change: () -> T): T = withFolderLock(dir, change)

    /**
     * Each write has its own temporary file, so two writers never write into one (Audit A02): it is
     * written and synced under a name of its own, then renamed over the track.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun writeAtomically(target: String, text: String) {
        val tmp = "$target.${Uuid.random()}$TmpSuffix"
        try {
            CoreFiles.writeTextAtomically(tmp, text)
            if (!CoreFiles.move(tmp, target)) throw IOException("cannot rename $tmp to $target")
        } finally {
            CoreFiles.delete(tmp)
        }
    }

    private companion object {
        const val Suffix = ".track.json"
        const val UnreadableMarker = ".unreadable-"
        const val TmpSuffix = ".tmp"

        @OptIn(ExperimentalTime::class)
        fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
