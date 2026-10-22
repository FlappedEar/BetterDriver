package com.flappedear.betterdriver.timing.track

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * replaced whole. Blocking; call it off the main thread.
 */
@Suppress("TooManyFunctions") // One entry point per thing kept: tracks, their corners and their corrections.
class TrackStore(private val dir: File) {

    /** Every readable track, by name. Unreadable files are moved aside and reported in [setAside]. */
    fun all(): List<Track> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        return files.mapNotNull(::readOrSetAside).sortedBy { it.displayName.lowercase() }
    }

    fun get(id: String): Track? {
        val file = fileFor(id)
        return if (file.isFile) readOrSetAside(file) else null
    }

    /**
     * Saves [track], replacing any track with the same id. The stored copy has the next revision
     * and [nowUtcMillis] as its update time; it is returned. The stored corner identity (FBBD-172) is
     * kept: only [updateIdentity] changes it, so an editor or an import holding an older one, or none,
     * never renames the corners here. A new track keeps the identity it comes with.
     *
     * With [adoptIdentity], for a track received from the other device with the driver's corner
     * corrections (FBBD-179), its identity replaces the one here as the next revision after it
     * ([adoptedOver]).
     */
    fun save(
        track: Track,
        nowUtcMillis: Long = System.currentTimeMillis(),
        adoptIdentity: Boolean = false,
    ): Track = locked {
        val previous = get(track.id)
        val received = track.identity?.takeIf { adoptIdentity }
        val stored = track.copy(
            revision = maxOf(track.revision, (previous?.revision ?: 0) + 1),
            updatedUtcMillis = nowUtcMillis,
            identity = received?.adoptedOver(previous?.identity) ?: previous?.identity ?: track.identity,
        )
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
        nowUtcMillis: Long = System.currentTimeMillis(),
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
        return dir.isDirectory && locked { file.delete() }
    }

    /** Files moved aside because they could not be read. */
    fun setAside(): List<File> = dir.listFiles { f -> f.name.contains(UnreadableMarker) }.orEmpty().toList()

    private fun fileFor(id: String): File {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_" }) { "bad track id: $id" }
        return File(dir, id + Suffix)
    }

    private fun readOrSetAside(file: File): Track? = try {
        TrackJson.decode(file.readText()).takeIf { it.id + Suffix == file.name }
            ?: throw TrackFormatException("id does not match file name")
    } catch (_: TrackFormatException) {
        setAside(file)
    } catch (_: IOException) {
        setAside(file)
    }

    /** Renamed so it no longer ends in [Suffix] and is not read again. */
    private fun setAside(file: File): Track? {
        file.renameTo(File(dir, file.name + UnreadableMarker + System.currentTimeMillis()))
        return null
    }

    /**
     * Runs [change] holding this folder's monitor (threads of this process) and its file lock (other
     * processes). The lock file sits next to the folder, so the folder holds only tracks and a backup
     * or restore of it never meets a lock file.
     */
    private fun <T> locked(change: () -> T): T {
        dir.mkdirs()
        val canonical = dir.canonicalFile
        val monitor = monitors.computeIfAbsent(canonical.path) { Any() }
        return synchronized(monitor) {
            val lockFile = File(canonical.parentFile, canonical.name + LockSuffix)
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use { change() }
            }
        }
    }

    /** Each write has its own temporary file, so two writers never write into one (Audit A02). */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(dir, "${target.name}.${UUID.randomUUID()}$TmpSuffix")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            move(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun move(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val Suffix = ".track.json"
        const val UnreadableMarker = ".unreadable-"
        const val TmpSuffix = ".tmp"
        const val LockSuffix = ".lock"

        /** One monitor per tracks folder, shared by every instance in this process. */
        val monitors = ConcurrentHashMap<String, Any>()
    }
}
