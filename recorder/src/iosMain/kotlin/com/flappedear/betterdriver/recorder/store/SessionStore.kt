package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

private const val IdSuffixChars = 8
private const val MillisPerSecond = 1_000.0

/** Name prefix of a session directory being deleted; such directories are never listed. */
private const val DeletingPrefix = ".deleting-"

/** One stored session as the list shows it, as SessionSummary on Android. */
data class StoredSession(
    val id: String,
    val dir: String,
    val info: SessionInfo,
    val state: SessionState,
    /** From the clean stop, or from the last sample found by recovery. Null while recording or before recovery. */
    val durationMicros: Long? = null,
)

enum class DeleteResult { DELETED, NOT_FOUND, RECORDING, FAILED }

/**
 * Session directories under [root] on iOS, one per recording, in the same layout and file format
 * as the Android store (SessionFiles), so a session moves between the two unchanged (FBBD-332).
 */
class SessionStore(val root: String) {

    init {
        PosixFiles.mkdirs(root)
        // A delete cut short by a crash leaves a hidden directory; finish it.
        PosixFiles.list(root).filter { it.startsWith(DeletingPrefix) }
            .forEach { PosixFiles.deleteRecursively(PosixFiles.join(root, it)) }
    }

    fun dir(id: String) = PosixFiles.join(root, id)

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

    fun state(dir: String): SessionState = when {
        PosixFiles.isFile(PosixFiles.join(dir, SessionFileNames.FINISHED)) -> SessionState.FINISHED
        PosixFiles.isLocked(PosixFiles.join(dir, SessionFileNames.LOCK)) -> SessionState.RECORDING
        PosixFiles.isFile(PosixFiles.join(dir, SessionFileNames.RECOVERY)) -> SessionState.RECOVERED
        else -> SessionState.INTERRUPTED
    }

    private fun props(path: String): Map<String, String>? = try {
        if (PosixFiles.isFile(path)) parseProperties(PosixFiles.readBytes(path)) else null
    } catch (_: FileError) {
        null // unreadable: treated as missing
    } catch (_: IllegalArgumentException) {
        null // malformed escape in the file
    }

    /** Sessions with readable metadata, newest first. */
    fun list(): List<StoredSession> = PosixFiles.list(root)
        .filter { !it.startsWith(".") && PosixFiles.isDirectory(dir(it)) }
        .mapNotNull { id ->
            val d = dir(id)
            val p = props(PosixFiles.join(d, SessionFileNames.META)) ?: return@mapNotNull null
            val info = try {
                metaFromEntries(p)
            } catch (_: RuntimeException) {
                return@mapNotNull null // damaged metadata: not listed
            }
            val duration = durationMicrosOf(
                props(PosixFiles.join(d, SessionFileNames.FINISHED)),
                props(PosixFiles.join(d, SessionFileNames.RECOVERY)),
            )
            StoredSession(id, d, info, state(d), duration)
        }
        .sortedByDescending { it.info.startUtcMillis }

    private fun chunkPaths(dir: String): List<String> =
        PosixFiles.list(dir).filter(SessionFileNames::isChunk).sorted().map { PosixFiles.join(dir, it) }

    /**
     * Run at app start. Finds sessions that were not stopped cleanly and nobody is recording,
     * checks their chunks and marks them recovered. Chunk files are only read, never changed (FBBD-71).
     */
    fun recoverInterrupted(): List<RecoveryReport> = list().filter { it.state == SessionState.INTERRUPTED }.map { s ->
        val report = recoveryReportOf(s.id, chunkPaths(s.dir).map { path -> { PosixFiles.readBytes(path) } })
        val now = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()
        PosixFiles.writeAtomically(
            PosixFiles.join(s.dir, SessionFileNames.RECOVERY),
            writeProperties(recoveryEntries(report, now)),
        )
        report
    }

    /**
     * Reads session [id], in any state, including one being recorded (up to its last write).
     * With [samples] false the sample sets are left out.
     */
    fun read(id: String, samples: Boolean = true): SessionData {
        val d = dir(id)
        val p = props(PosixFiles.join(d, SessionFileNames.META)) ?: error("no readable ${SessionFileNames.META} in $d")
        val chunks = chunkPaths(d).asSequence().map(PosixFiles::readBytes)
        return sessionDataOf(id, metaFromEntries(p), state(d), chunks, samples)
    }

    /**
     * Deletes session [id] with everything in its directory. A session being recorded is refused.
     * The directory is first renamed to a hidden name, so it leaves the list at once and a delete cut
     * short is finished by the next [SessionStore].
     */
    fun delete(id: String): DeleteResult {
        val target = dir(id)
        val doomed = PosixFiles.join(root, "$DeletingPrefix$id")
        return when {
            id.isEmpty() || id.startsWith(".") || '/' in id || !PosixFiles.isDirectory(target) -> DeleteResult.NOT_FOUND
            state(target) == SessionState.RECORDING -> DeleteResult.RECORDING
            !PosixFiles.move(target, doomed) -> DeleteResult.FAILED
            else -> {
                PosixFiles.syncDir(root)
                PosixFiles.deleteRecursively(doomed)
                DeleteResult.DELETED
            }
        }
    }
}
