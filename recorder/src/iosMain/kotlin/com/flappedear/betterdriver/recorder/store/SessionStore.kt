package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.FileError
import com.flappedear.betterdriver.io.PosixFiles
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

private const val IdSuffixChars = 8
private const val MillisPerSecond = 1_000.0

/**
 * Session directories under [root] on iOS, one per recording, in the same layout and file format
 * as the Android store (SessionFiles), so a session moves between the two unchanged (FBBD-332).
 * Listing, recovery, reading and deleting are the shared core's [SessionFolders] (FBBD-359).
 */
class SessionStore(val root: String) {
    private val folders = SessionFolders(root)

    init {
        PosixFiles.mkdirs(root)
        folders.finishDeletes()
    }

    fun dir(id: String) = folders.dir(id)

    /**
     * Creates the directory, takes the session lock and writes the metadata before any sample is
     * recorded (FBBD-70). The id starts with the start's local date and time, as on Android.
     * Returns the id, the directory and the lock's descriptor, which the recording holds.
     */
    internal fun createSession(info: SessionInfo): Triple<String, String, Int> {
        val stamp = NSDateFormatter().apply {
            dateFormat = "yyyyMMdd-HHmmss"
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
            timeZone = NSTimeZone.localTimeZone
        }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(info.startUtcMillis / MillisPerSecond))
        val id = "$stamp-${NSUUID().UUIDString.lowercase().take(IdSuffixChars)}"
        val dir = dir(id)
        check(PosixFiles.mkdirs(dir)) { "could not create $dir" }
        PosixFiles.syncDir(root)
        val lock = checkNotNull(PosixFiles.tryLock(PosixFiles.join(dir, SessionFileNames.LOCK))) { "$dir is locked" }
        try {
            val meta = writeProperties(metaEntries(id, info))
            PosixFiles.writeAtomically(PosixFiles.join(dir, SessionFileNames.META), meta)
        } catch (e: FileError) {
            PosixFiles.unlock(lock)
            throw e
        }
        return Triple(id, dir, lock)
    }

    fun state(dir: String): SessionState = folders.state(dir)

    /** Sessions with readable metadata, newest first. */
    fun list(): List<StoredSession> = folders.list()

    /** Run at app start: marks sessions that were not stopped cleanly as recovered (FBBD-71). */
    fun recoverInterrupted(onError: (id: String, e: Exception) -> Unit = { _, _ -> }): List<RecoveryReport> =
        folders.recoverInterrupted(onError)

    /**
     * Reads session [id], in any state, including one being recorded (up to its last write).
     * With [samples] false the sample sets are left out.
     */
    fun read(id: String, samples: Boolean = true): SessionData = folders.read(dir(id), samples)

    /** Deletes session [id] with everything in its directory. A session being recorded is refused. */
    fun delete(id: String): DeleteResult = folders.delete(id)
}
