@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.recorder.store.SessionFolders
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.iosTrackStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970

/**
 * Back up and Restore on the iPhone and iPad (FBBD-372), Android's Settings › App › Back up and
 * Restore: the shared [BackupWriter] and [BackupRestorer] over the app's Application Support folder
 * [base], which has Android's layout (sessions, tracks, setup.json, coach). Files are read and
 * written with stdio here, as session bundles are (FBBD-393). Blocking: call off the main thread.
 */
class IosBackups(private val base: String) {
    private val staging = "$base/backup-staging"
    private val restorer = BackupRestorer(base, staging)

    /** Sessions "Include sessions" takes and their bytes without video. */
    fun sessionsSize(): BackupSessionsSize = BackupContents.sessionsSize(base)

    /**
     * Writes a backup to [outRoot]/[fileName] and returns its path. No preferences go in: the
     * iPhone keeps none of the Android app's. [onProgress] gets file bytes written and their total.
     */
    @Throws(Exception::class)
    @Suppress("TooGenericExceptionCaught") // Any failure removes the part written and is passed on.
    fun write(
        outRoot: String,
        fileName: String,
        includeSessions: Boolean,
        appVersion: String,
        onProgress: (written: Long, total: Long) -> Unit,
    ): String {
        mkdirs(outRoot)
        val path = "$outRoot/$fileName"
        val part = "$path.part"
        NSFileManager.defaultManager.removeItemAtPath(part, null)
        val out = FileWriter(part)
        try {
            BackupWriter.write(
                root = base,
                includeSessions = includeSessions,
                appVersion = appVersion,
                nowUtcMillis = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong(),
                prefs = null,
                out = { b, from, to -> out.write(b, from, to) },
                readFile = { file, block -> pump(file, block) },
                onProgress = onProgress,
            )
            out.close()
        } catch (e: Exception) {
            try {
                out.close(sync = false)
            } catch (_: ImportFileException) {
                // The first failure is the one passed on.
            }
            NSFileManager.defaultManager.removeItemAtPath(part, null)
            throw e
        }
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        if (!NSFileManager.defaultManager.moveItemAtPath(part, path, null)) {
            throw ImportFileException("could not prepare $path")
        }
        return path
    }

    /** Unpacks and checks the backup at [path]; [onProgress] gets bytes read and the file's size. */
    @Throws(Exception::class)
    @Suppress("TooGenericExceptionCaught") // An open file is closed on any failure, then it is passed on.
    fun open(path: String, onProgress: (read: Long, total: Long) -> Unit): StagedBackup {
        val total = sizeOf(path)
        mkdirs(staging)
        var files: BackupStaging? = null
        try {
            return restorer.unpack({ dir -> BackupStaging(dir, staging).also { files = it } }) { feed ->
                var read = 0L
                pump(path) { bytes, n ->
                    feed(bytes, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        } catch (e: Exception) {
            files?.abandon()
            throw e
        }
    }

    /**
     * Restores [backup], merged or replacing ([replace]). Refused while a session records, checked
     * again just before anything changes.
     */
    @Throws(Exception::class)
    fun restore(backup: StagedBackup, replace: Boolean): RestoreResult {
        refuseWhileRecording()
        return restorer.restore(
            backup,
            if (replace) RestoreMode.REPLACE else RestoreMode.MERGE,
            beforeCommit = ::refuseWhileRecording,
        )
    }

    /** Drops a checked backup that will not be restored. */
    fun discard(backup: StagedBackup) = restorer.discard(backup)

    /**
     * At app start, before anything reads the data: finishes a restore the app was stopped in after
     * its commit, else removes what an unfinished one left.
     */
    @Throws(Exception::class)
    fun finishOrClear() {
        if (!restorer.finishPending()) restorer.clearStaging()
    }

    private fun refuseWhileRecording() {
        val folders = SessionFolders(CoreFiles.join(base, BackupFormat.Sessions))
        if (folders.list().any { it.state == SessionState.RECORDING }) {
            throw ImportFileException("a session is recording. Restore after it ends.")
        }
    }

    private companion object {
        const val MillisPerSecond = 1000.0
    }
}

/** A backup's files unpacked into [dir], one at a time, with stdio. */
private class BackupStaging(private val dir: String, private val volume: String) : StagingFiles {
    private var out: FileWriter? = null

    override fun create(path: String) {
        val full = "$dir/$path"
        mkdirs(full.substringBeforeLast('/'))
        out = FileWriter(full)
    }

    override fun write(bytes: ByteArray, from: Int, to: Int) {
        checkNotNull(out).write(bytes, from, to)
    }

    override fun close() {
        val file = out
        out = null
        file?.close()
    }

    fun abandon() {
        val file = out
        out = null
        try {
            file?.close(sync = false)
        } catch (_: ImportFileException) {
            // Abandoned: its files are removed already.
        }
    }

    override fun freeBytes(): Long {
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(volume, null)
        return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: Long.MAX_VALUE
    }
}

/**
 * Tracks as single files between devices on the iPhone and iPad (FBBD-80, FBBD-372), as Android's
 * track Share and Tracks › Import: the shared [TrackShare] file, [trackMatch] and the TrackStore in
 * [tracksRoot].
 */
class IosTrackShare(private val tracksRoot: String) {
    private val store get() = iosTrackStore(tracksRoot)

    /** Writes saved track [id] as a track file in [outRoot] and returns its path. */
    @Throws(Exception::class)
    fun write(id: String, outRoot: String): String {
        val track = store.get(id) ?: throw ImportFileException("no track $id")
        mkdirs(outRoot)
        val path = "$outRoot/${TrackShare.fileName(track)}"
        CoreFiles.writeBytesAtomically(path, TrackShare.encode(track))
        return path
    }

    /** Reads the track file at [path]; refuses anything else. */
    @Throws(Exception::class)
    fun read(path: String): Track {
        if (sizeOf(path) > TrackShare.MaxTrackBytes) throw BundleDamagedException("file too large for a track")
        val track = TrackShare.decode(CoreFiles.readBytes(path))
        if (!BackupFormat.validTrackId(track.id)) throw BundleDamagedException("bad track id")
        return track
    }

    /** How [track] compares with the saved track of its id. */
    fun match(track: Track): TrackMatch = trackMatch(track, store.get(track.id))

    /**
     * Keeps [track], over the saved one of its id; with the driver's corner corrections if it carries
     * them (FBBD-179).
     */
    @Throws(Exception::class)
    fun save(track: Track) {
        store.save(track, adoptIdentity = carriesCorrectionsOf(track))
    }
}
