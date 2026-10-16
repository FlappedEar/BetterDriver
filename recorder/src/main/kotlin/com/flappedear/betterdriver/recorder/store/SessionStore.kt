package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID

enum class SessionState {
    /** A process holds the session lock and is writing to it. */
    RECORDING,
    /** Stopped cleanly. */
    FINISHED,
    /** Not stopped cleanly and not yet recovered (a crash, kill or power loss). */
    INTERRUPTED,
    /** Interrupted, then recovered. Exports like a finished session. */
    RECOVERED,
}

data class SessionSummary(
    val id: String,
    val dir: File,
    val info: SessionInfo,
    val state: SessionState,
    /** From the clean stop, or from the last sample found by recovery. Null while recording or before recovery. */
    val durationMicros: Long? = null,
)

data class RecoveryReport(
    val id: String,
    val chunks: Int,
    val records: Long,
    val sampleSets: Long,
    val lastSampleMicros: Long?,
    /** Bytes of incomplete frames at chunk ends (dropped). */
    val truncatedBytes: Long,
    /** Bytes of damaged frames inside chunks (skipped). */
    val skippedBytes: Long,
)

/** Everything read back from a session. */
data class SessionData(
    val id: String,
    val info: SessionInfo,
    val state: SessionState,
    val channels: Map<Int, ChannelDef>,
    val sampleSets: List<SampleSet>,
    val events: List<RecorderEvent>,
    val end: Record.End?,
    val truncatedBytes: Long,
    val skippedBytes: Long,
)

private const val IdSuffixChars = 8

/**
 * [def], read from a chunk (which stores no provenance), with its provenance from [info]: the
 * metadata's entry for its id, which the recorder keeps up to date for every channel it writes
 * (FBBD-174), else one derived as for a session stored before provenance was ([Provenance.forSession]).
 */
private fun withProvenance(def: ChannelDef, info: SessionInfo): ChannelDef {
    val stored = info.channels.firstOrNull { it.id == def.id && it.name == def.name }?.provenance
    return def.copy(provenance = stored ?: Provenance.forSession(def, info.devices, info.imported))
}

/** Name prefix of a session directory being deleted; such directories are never listed. */
private const val DeletingPrefix = ".deleting-"

enum class DeleteResult { DELETED, NOT_FOUND, RECORDING, FAILED }

/** [target] is a directory directly under [root], and [id] is not a hidden name. */
private fun isSessionDir(root: File, id: String, target: File): Boolean =
    id.isNotEmpty() && !id.startsWith(".") && target.parentFile == root && target.isDirectory

/** Session directories under [root], one per recording. */
class SessionStore(
    val root: File,
    /** The time zone session ids are named in: the phone's own, read at each new session (FBBD-217). */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    init {
        root.mkdirs()
        // A delete cut short by a crash leaves a hidden directory; finish it.
        (root.listFiles { f -> f.isDirectory && f.name.startsWith(DeletingPrefix) } ?: emptyArray())
            .forEach { it.deleteRecursively() }
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

    fun state(dir: File): SessionState = when {
        File(dir, SessionFiles.FINISHED).isFile -> SessionState.FINISHED
        SessionFiles.isLocked(dir) -> SessionState.RECORDING
        File(dir, SessionFiles.RECOVERY).isFile -> SessionState.RECOVERED
        else -> SessionState.INTERRUPTED
    }

    /** Sessions with readable metadata, newest first. */
    fun list(): List<SessionSummary> =
        (root.listFiles { f -> f.isDirectory && !f.name.startsWith(DeletingPrefix) } ?: emptyArray())
            .mapNotNull { d ->
                val p = SessionFiles.readProps(File(d, SessionFiles.META)) ?: return@mapNotNull null
                val info = try {
                    SessionFiles.propsToMeta(p)
                } catch (_: RuntimeException) {
                    return@mapNotNull null // damaged metadata: not listed
                }
                SessionSummary(d.name, d, info, state(d), durationMicros(d))
            }
            .sortedByDescending { it.info.startUtcMillis }

    private fun durationMicros(dir: File): Long? {
        val finished = SessionFiles.readProps(File(dir, SessionFiles.FINISHED))?.getProperty("endMicros")
        val recovered = SessionFiles.readProps(File(dir, SessionFiles.RECOVERY))?.getProperty("lastSampleMicros")
        return (finished ?: recovered)?.toLongOrNull()
    }

    /**
     * Deletes session [id] with everything in its directory: chunks, markers and its video folder
     * (phone clips, downloaded and copied GoPro files, the clip index) (FBBD-90). A session that a
     * process is recording is refused. The directory is first renamed to a hidden name, so it
     * leaves the list at once and a delete cut short is finished by the next [SessionStore].
     */
    fun delete(id: String): DeleteResult {
        val target = dir(id)
        val doomed = File(root, "$DeletingPrefix$id")
        return when {
            !isSessionDir(root, id, target) -> DeleteResult.NOT_FOUND
            state(target) == SessionState.RECORDING -> DeleteResult.RECORDING
            !target.renameTo(doomed) -> DeleteResult.FAILED
            else -> {
                SessionFiles.syncDir(root)
                doomed.deleteRecursively()
                DeleteResult.DELETED
            }
        }
    }

    /**
     * Run at app start. Finds sessions that were not stopped cleanly and nobody is recording,
     * checks their chunks, and marks them recovered. Chunk files are only read, never changed (FBBD-71).
     */
    fun recoverInterrupted(): List<RecoveryReport> =
        list().filter { it.state == SessionState.INTERRUPTED }.map { recover(it.dir) }

    private fun recover(dir: File): RecoveryReport {
        var records = 0L
        var sets = 0L
        var last: Long? = null
        var truncated = 0L
        var skipped = 0L
        val chunks = SessionFiles.chunks(dir)
        for (c in chunks) {
            val r = Frames.scan(c.readBytes()) { rec ->
                if (rec is Record.Samples) {
                    sets++
                    last = maxOf(last ?: Long.MIN_VALUE, rec.set.tMicros)
                }
            }
            records += r.records
            truncated += r.truncatedTailBytes
            skipped += r.skippedBytes
        }
        val report = RecoveryReport(dir.name, chunks.size, records, sets, last, truncated, skipped)
        SessionFiles.writeAtomically(
            File(dir, SessionFiles.RECOVERY),
            Properties().apply {
                setProperty("recoveredAtUtcMillis", System.currentTimeMillis().toString())
                setProperty("chunks", report.chunks.toString())
                setProperty("records", report.records.toString())
                setProperty("sampleSets", report.sampleSets.toString())
                setProperty("lastSampleMicros", report.lastSampleMicros?.toString() ?: "")
                setProperty("truncatedBytes", report.truncatedBytes.toString())
                setProperty("skippedBytes", report.skippedBytes.toString())
            },
        )
        return report
    }

    /** Reads a whole session. Works for any state, including one still recording (up to its last sync). */
    fun read(id: String): SessionData = read(dir(id))

    /**
     * With [samples] false, the sample sets are left out: metadata, channels and events only. For
     * screens that only need events (cue counts, the track used), so an hour at 25 Hz is not held in memory.
     * With them, a receiver's GNSS values are put back at the time of their fix ([withGnssAtFixTime]).
     */
    fun read(dir: File, samples: Boolean = true): SessionData {
        val props = SessionFiles.readProps(File(dir, SessionFiles.META))
            ?: error("no readable ${SessionFiles.META} in ${dir.path}")
        val info = SessionFiles.propsToMeta(props)
        val channels = LinkedHashMap<Int, ChannelDef>()
        info.channels.forEach { channels[it.id] = it }
        val sets = ArrayList<SampleSet>()
        val events = ArrayList<RecorderEvent>()
        var end: Record.End? = null
        var truncated = 0L
        var skipped = 0L
        for (c in SessionFiles.chunks(dir)) {
            val r = Frames.scan(c.readBytes()) { rec ->
                when (rec) {
                    is Record.Channel -> channels[rec.def.id] = withProvenance(rec.def, info)
                    is Record.Samples -> if (samples) sets += rec.set
                    is Record.Event -> events += rec.event
                    is Record.End -> end = rec
                }
            }
            truncated += r.truncatedTailBytes
            skipped += r.skippedBytes
        }
        val data = SessionData(dir.name, info, state(dir), channels, sets, events, end, truncated, skipped)
        // The GNSS fix a receiver sends with its acceleration is older than it (FBBD-229): put it back at its time.
        return if (samples) data.withGnssAtFixTime() else data
    }
}
