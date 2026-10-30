package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.SessionInfo
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class SessionSummary(
    val id: String,
    val dir: File,
    val info: SessionInfo,
    val state: SessionState,
    /** From the clean stop, or from the last sample found by recovery. Null while recording or before recovery. */
    val durationMicros: Long? = null,
)

private const val IdSuffixChars = 8

/**
 * Session directories under [root], one per recording. Listing, recovery, reading and deleting are
 * the shared core's [SessionFolders], as on iOS (FBBD-359); this adds the File view the app uses.
 */
class SessionStore(
    val root: File,
    /** The time zone session ids are named in: the phone's own, read at each new session (FBBD-217). */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val folders = SessionFolders(root.path)

    init {
        root.mkdirs()
        folders.finishDeletes()
    }

    /**
     * Creates the directory and writes the metadata before any sample is recorded (FBBD-70).
     * [beforeMeta] runs on the new directory first; the recorder takes the session lock there, so the
     * session is never listed (and never mistaken for an interrupted one) before it is locked.
     *
     * The id starts with the start's date and time as the phone showed it (local time), so folders
     * and file names read like the clock in the car. Nothing reads the time back from an id: the
     * start instant is in the metadata, and the list is ordered by it.
     */
    fun createSession(info: SessionInfo, beforeMeta: (File) -> Unit = {}): Pair<String, File> {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(zone())
            .format(Instant.ofEpochMilli(info.startUtcMillis))
        val id = "$stamp-${UUID.randomUUID().toString().take(IdSuffixChars)}"
        val dir = File(root, id)
        check(dir.mkdirs()) { "could not create ${dir.path}" }
        SessionFiles.syncDir(root)
        beforeMeta(dir)
        SessionFiles.writeAtomically(File(dir, SessionFiles.META), SessionFiles.metaToProps(id, info))
        return id to dir
    }

    fun dir(id: String) = File(root, id)

    fun state(dir: File): SessionState = folders.state(dir.path)

    /** Sessions with readable metadata, newest first. */
    fun list(): List<SessionSummary> =
        folders.list().map { SessionSummary(it.id, File(it.dir), it.info, it.state, it.durationMicros) }

    /**
     * Deletes session [id] with everything in its directory: chunks, markers and its video folder
     * (phone clips, downloaded and copied GoPro files, the clip index) (FBBD-90). A session that a
     * process is recording is refused.
     */
    fun delete(id: String): DeleteResult = folders.delete(id)

    /**
     * Run at app start. Finds sessions that were not stopped cleanly and nobody is recording,
     * checks their chunks, and marks them recovered. Chunk files are only read, never changed (FBBD-71).
     */
    fun recoverInterrupted(onError: (id: String, e: Exception) -> Unit = { _, _ -> }): List<RecoveryReport> =
        folders.recoverInterrupted(onError)

    /** Reads a whole session. Works for any state, including one still recording (up to its last sync). */
    fun read(id: String): SessionData = read(dir(id))

    /**
     * With [samples] false, the sample sets are left out: metadata, channels and events only. For
     * screens that only need events (cue counts, the track used), so an hour at 25 Hz is not held in memory.
     * With them, a receiver's GNSS values are put back at the time of their fix ([withGnssAtFixTime]).
     */
    fun read(dir: File, samples: Boolean = true): SessionData = folders.read(dir.path, samples)
}
