@file:OptIn(ExperimentalTime::class)

package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Name prefix of a session directory being deleted; such directories are never listed. */
internal const val DeletingPrefix = ".deleting-"

/** One stored session as the list shows it. */
data class StoredSession(
    val id: String,
    val dir: String,
    val info: SessionInfo,
    val state: SessionState,
    /** From the clean stop, or from the last sample found by recovery. Null while recording or before recovery. */
    val durationMicros: Long? = null,
)

enum class DeleteResult { DELETED, NOT_FOUND, RECORDING, FAILED }

/** Whether a process holds the session lock in [dir]: it is still being recorded. */
internal expect fun sessionLocked(dir: String): Boolean

/** Best effort: makes a create or rename in [dir] durable. */
internal expect fun syncFolder(dir: String)

/**
 * The session directories under [root], one per recording, read, listed, recovered and deleted the
 * same way by the Android app and the iOS app (FBBD-359): the layout is SessionFileNames', so a
 * session moves between the two unchanged (FBBD-332). Creating a session and recording it stay with
 * each platform's recorder.
 */
class SessionFolders(val root: String) {

    /** A delete cut short by a crash leaves a hidden directory; finishes it. */
    fun finishDeletes() {
        CoreFiles.list(root).filter { it.startsWith(DeletingPrefix) }
            .forEach { CoreFiles.deleteRecursively(CoreFiles.join(root, it)) }
    }

    fun dir(id: String) = CoreFiles.join(root, id)

    fun state(dir: String): SessionState = when {
        CoreFiles.isFile(CoreFiles.join(dir, SessionFileNames.FINISHED)) -> SessionState.FINISHED
        sessionLocked(dir) -> SessionState.RECORDING
        CoreFiles.isFile(CoreFiles.join(dir, SessionFileNames.RECOVERY)) -> SessionState.RECOVERED
        else -> SessionState.INTERRUPTED
    }

    /** The entries of the properties file at [path]; null when it is missing or cannot be read. */
    fun props(path: String): Map<String, String>? = try {
        if (CoreFiles.isFile(path)) parseProperties(CoreFiles.readBytes(path)) else null
    } catch (_: IOException) {
        null // unreadable: treated as missing
    } catch (_: IllegalArgumentException) {
        null // malformed escape in the file
    }

    /** Sessions with readable metadata, newest first. */
    fun list(): List<StoredSession> = CoreFiles.list(root)
        .filter { !it.startsWith(".") && CoreFiles.isDirectory(dir(it)) }
        .mapNotNull { id ->
            val d = dir(id)
            val p = props(CoreFiles.join(d, SessionFileNames.META)) ?: return@mapNotNull null
            val info = try {
                metaFromEntries(p)
            } catch (_: RuntimeException) {
                return@mapNotNull null // damaged metadata: not listed
            }
            val duration = durationMicrosOf(
                props(CoreFiles.join(d, SessionFileNames.FINISHED)),
                props(CoreFiles.join(d, SessionFileNames.RECOVERY)),
            )
            StoredSession(id, d, info, state(d), duration)
        }
        .sortedByDescending { it.info.startUtcMillis }

    /** The chunk files of the session in [dir], in recording order. */
    fun chunkPaths(dir: String): List<String> =
        CoreFiles.list(dir).filter(SessionFileNames::isChunk).sorted().map { CoreFiles.join(dir, it) }

    /**
     * Run at app start. Finds sessions that were not stopped cleanly and nobody is recording,
     * checks their chunks and marks them recovered. Chunk files are only read, never changed (FBBD-71).
     * Each session is on its own: one that cannot be recovered is left as it is and the rest still
     * are; [onError] hears about it. A session whose recording took its lock meanwhile is left alone.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure of one session must not stop the others.
    fun recoverInterrupted(onError: (id: String, e: Exception) -> Unit = { _, _ -> }): List<RecoveryReport> =
        list().filter { it.state == SessionState.INTERRUPTED }.mapNotNull { s ->
            try {
                val report = recoveryReportOf(s.id, chunkPaths(s.dir).map { path -> { CoreFiles.readBytes(path) } })
                if (state(s.dir) != SessionState.INTERRUPTED) return@mapNotNull null
                val now = Clock.System.now().toEpochMilliseconds()
                CoreFiles.writeBytesAtomically(
                    CoreFiles.join(s.dir, SessionFileNames.RECOVERY),
                    writeProperties(recoveryEntries(report, now)),
                )
                syncFolder(s.dir)
                report
            } catch (e: Exception) {
                onError(s.id, e)
                null
            }
        }

    /**
     * Reads the session in [dir], in any state, including one being recorded (up to its last write).
     * With [samples] false the sample sets are left out: metadata, channels and events only, for
     * screens that only need events, so an hour at 25 Hz is not held in memory.
     */
    fun read(dir: String, samples: Boolean = true): SessionData {
        val p = props(CoreFiles.join(dir, SessionFileNames.META))
            ?: error("no readable ${SessionFileNames.META} in $dir")
        val chunks = chunkPaths(dir).asSequence().map(CoreFiles::readBytes)
        return sessionDataOf(dir.trimEnd('/').substringAfterLast('/'), metaFromEntries(p), state(dir), chunks, samples)
    }

    /**
     * Deletes session [id] with everything in its directory: chunks, markers and its video folder
     * (FBBD-90). A session being recorded is refused. The directory is first renamed to a hidden
     * name, so it leaves the list at once and a delete cut short is finished by [finishDeletes].
     */
    fun delete(id: String): DeleteResult {
        val target = dir(id)
        val doomed = CoreFiles.join(root, "$DeletingPrefix$id")
        return when {
            id.isEmpty() || id.startsWith(".") || '/' in id || !CoreFiles.isDirectory(target) -> DeleteResult.NOT_FOUND
            state(target) == SessionState.RECORDING -> DeleteResult.RECORDING
            !CoreFiles.move(target, doomed) -> DeleteResult.FAILED
            else -> {
                syncFolder(root)
                CoreFiles.deleteRecursively(doomed)
                DeleteResult.DELETED
            }
        }
    }
}
