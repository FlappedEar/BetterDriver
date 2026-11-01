package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.io.FileError
import com.flappedear.betterdriver.io.PosixFiles
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.finishedEntries
import com.flappedear.betterdriver.recorder.store.metaEntries
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import platform.posix.usleep

private const val Mib = 1L shl 20
private const val DefaultSyncMs = 500L
private const val DefaultChunkMib = 8L
private const val DefaultChunkAgeMs = 60_000L
private const val DefaultWarnMib = 200L
private const val DefaultStopMib = 50L
private const val DefaultStorageCheckMs = 2_000L
private const val MaxSyncIntervalMs = 1_000L
private const val NanosPerMilli = 1_000_000L
private const val MicrosPerMilli = 1_000L
private const val DefaultQueueCapacity = 100_000

/**
 * As RecorderConfig on Android: sources hand records to a write queue and return, and one writer
 * puts them into the files (FBBD-350 R2).
 */
data class RecorderConfig(
    /** Written data is fsynced at least this often, so a power loss loses at most about this much (FBBD-70). */
    val syncIntervalMs: Long = DefaultSyncMs,
    val chunkMaxBytes: Long = DefaultChunkMib * Mib,
    val chunkMaxAgeMs: Long = DefaultChunkAgeMs,
    /** Below this much free space the recorder warns. */
    val storageWarnBytes: Long = DefaultWarnMib * Mib,
    /** Below this much free space the recorder stops cleanly, keeping what it has. */
    val storageStopBytes: Long = DefaultStopMib * Mib,
    val storageCheckIntervalMs: Long = DefaultStorageCheckMs,
    /** Records waiting for the writer, at most; beyond it a record is counted as dropped, as on Android. */
    val queueCapacity: Int = DefaultQueueCapacity,
) {
    init {
        require(syncIntervalMs in 1..MaxSyncIntervalMs) { "syncIntervalMs must be 1..1000 to meet the 1 s loss limit" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }
}

/**
 * Crash-safe session recorder for iOS (FBBD-332), writing the same session files as the Android
 * recorder. Samples are appended to chunk files as they arrive; nothing waits for a final save.
 */
class Recorder(private val store: SessionStore, private val config: RecorderConfig = RecorderConfig()) {
    fun start(info: SessionInfo): Recording {
        // One snapshot, so a caller's later changes do not reach the session.
        val snapshot = info.copy(devices = info.devices.toList(), channels = info.channels.toList())
        val (id, dir, lock) = store.createSession(snapshot)
        return Recording(id, dir, snapshot, lock, config)
    }
}

/**
 * One running recording. Sources push into it through [SampleSink] from any thread, such as the
 * CoreLocation or Bluetooth callback; each call only puts its record on the write queue and returns
 * (FBBD-350 R2: a slow write or fsync used to hold the source's callback, and with it the screen).
 * One writer, on a serial queue of its own, appends the records to the current chunk with write(2) in
 * the order they came, and the chunk is fsynced at least every [RecorderConfig.syncIntervalMs], also
 * when no more data comes (a timer on a background queue). An app killed loses only what still waits
 * in the queue, normally well under a millisecond of data, as Android's writer queue. A full queue
 * drops the record and counts it ([EventKinds.DROPPED_SAMPLES], droppedSamples in finished.properties).
 * A write or fsync failure ends the recording with reason "error" and leaves the files for recovery.
 */
// The sink, stop and failure paths of one recording share its queue and file; splitting it would hide the ordering.
@Suppress("TooManyFunctions")
class Recording internal constructor(
    val id: String,
    val dir: String,
    info: SessionInfo,
    private var lockFd: Int,
    private val config: RecorderConfig,
) : SampleSink {
    private val started = TimeSource.Monotonic.markNow()

    /** A record on the write queue, with when it was handed in. */
    private class Queued(val record: Record, val atMicros: Long)

    // The intake: held only to add or take records, never across a write, so a source never waits on the disk.
    private val intakeLock = NSLock()
    private val queue = ArrayDeque<Queued>()
    private var accepting = true
    private var drainScheduled = false
    private var dropped = 0L

    // The file side: the writer, the sync timer and stop take it; everything below is under it.
    private val files = NSRecursiveLock()
    private val writer = dispatch_queue_create("com.flappedear.betterdriver.recorder.writer", null)
    private var meta = info
    private val channels = LinkedHashMap<Int, ChannelDef>()
    private var index = 0
    private var fd = -1
    private var path = ""
    private var chunkBytes = 0L
    private var chunkOpenedMs = 0L
    private var lastSyncMs = 0L
    private var dirty = false
    private var lastStorageCheckMs: Long? = null
    private var storageWarned = false
    private var rotate = true
    private var reportedDrops = 0L
    private var syncs = 0
    private val stats = WriterStats()

    /** Why it ended, once it has: "user" or another stop reason, "storage_full" or "error". Read without waiting. */
    @Volatile
    var endReason: String? = null
        private set

    /** The failure behind an "error" end. */
    @Volatile
    var error: Throwable? = null
        private set

    /** Called once, on the thread that ended it, with [endReason]. */
    var onEnded: ((String) -> Unit)? = null

    /** Called once when free space falls below [RecorderConfig.storageWarnBytes], with the bytes left. */
    var onStorageLow: ((Long) -> Unit)? = null

    /** Tests only: each frame write takes this much longer, as on a slow or nearly full disk. */
    @Volatile
    internal var writeDelayMicrosForTest: Int = 0

    /** Tests only: the app is gone; the writer stops between two frames, as the process would. */
    @Volatile
    private var killedForTest = false

    init {
        try {
            info.channels.forEach { channels[it.id] = it }
            openChunk()
        } catch (e: FileError) {
            PosixFiles.unlock(lockFd)
            throw e
        }
        scheduleSync()
    }

    /**
     * Syncs written data every [RecorderConfig.syncIntervalMs] even when no record arrives to do it,
     * e.g. after a source drops; stops once the recording has ended.
     */
    private fun scheduleSync() {
        val at = dispatch_time(DISPATCH_TIME_NOW, config.syncIntervalMs * NanosPerMilli)
        dispatch_after(at, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val running = onFiles {
                if (endReason == null && elapsedMs() - lastSyncMs >= config.syncIntervalMs) syncOrFail()
                endReason == null
            }
            if (running) scheduleSync()
        }
    }

    private fun elapsedMs() = started.elapsedNow().inWholeMilliseconds

    override fun nowMicros(): Long = started.elapsedNow().inWholeMicroseconds

    val isRecording: Boolean get() = endReason == null

    /**
     * How the writer kept up, for the log line at the stop: the longest a record waited in the queue,
     * the longest one batch of writes took, the most records waiting at once, and the records dropped.
     */
    val writerText: String get() = onFiles { stats.text(intake { dropped }) }

    override fun defineChannel(def: ChannelDef) = enqueue(Record.Channel(def))

    override fun submit(set: SampleSet) = enqueue(Record.Samples(set.copy(values = set.values.toList())))

    override fun event(kind: String, text: String) = enqueue(Record.Event(RecorderEvent(nowMicros(), kind, text)))

    /** An event stamped by its source at [tMicros] on this recording's clock. */
    fun eventAt(tMicros: Long, kind: String, text: String) = enqueue(Record.Event(RecorderEvent(tMicros, kind, text)))

    /**
     * Ends the recording cleanly: what the queue already took is written, then an end record, a final
     * fsync and finished.properties; what comes after is not taken. Returns when that is on disk.
     * False if it had already ended.
     */
    fun stop(reason: String = "user"): Boolean {
        // Intake closes first, before waiting for the file lock: a writer busy with a backlog would
        // otherwise keep taking new records, and the stop would wait for the sources to go quiet.
        intake { accepting = false }
        return onFiles { stopNow(reason) }
    }

    private fun stopNow(reason: String): Boolean {
        if (endReason != null) return false
        write(takeAll())
        if (endReason != null) return false
        finish(reason)
        return true
    }

    /** Tests only: whether written data still waits for its fsync. */
    internal val isDirtyForTest: Boolean get() = onFiles { dirty }

    /** Waits until the writer has written everything handed in so far (not its fsync); for tests and checks. */
    fun awaitWritten() = dispatch_sync(writer) {}

    /** Tests only: fsyncs of written data so far. */
    internal val syncsForTest: Int get() = onFiles { syncs }

    /** Tests only: records waiting for the writer. */
    internal val queuedForTest: Int get() = intake { queue.size }

    /** Tests only: closes the chunk behind the recorder's back, so its next fsync fails. */
    internal fun breakFileForTest() = onFiles {
        platform.posix.close(fd)
    }

    /**
     * Tests only: drops the files and the lock as a killed app would, without an end record. What
     * still waits in the queue is lost, as in a killed app; the writer stops after its current frame.
     */
    internal fun abandonForTest() {
        killedForTest = true
        intake {
            accepting = false
            queue.clear()
        }
        onFiles {
            if (fd >= 0) platform.posix.close(fd)
            fd = -1
            if (lockFd >= 0) PosixFiles.unlock(lockFd)
            lockFd = -1
            endReason = "abandoned"
        }
    }

    private inline fun <T> onFiles(body: () -> T): T {
        files.lock()
        try {
            return body()
        } finally {
            files.unlock()
        }
    }

    private inline fun <T> intake(body: () -> T): T {
        intakeLock.lock()
        try {
            return body()
        } finally {
            intakeLock.unlock()
        }
    }

    /** Puts [record] on the write queue and wakes the writer; never waits for the disk. */
    private fun enqueue(record: Record) {
        val wake = intake {
            when {
                !accepting -> false
                // A definition is never dropped: the samples after it need it to be read back.
                queue.size >= config.queueCapacity && record !is Record.Channel -> {
                    dropped++
                    false
                }
                else -> {
                    queue.addLast(Queued(record, nowMicros()))
                    stats.waiting(queue.size)
                    val first = !drainScheduled
                    drainScheduled = true
                    first
                }
            }
        }
        if (wake) dispatch_async(writer) { drain() }
    }

    /**
     * The writer: writes queued records until the queue is empty. Each batch is taken under the file
     * lock, so a stop never overtakes a batch already taken from the queue.
     */
    private fun drain() {
        while (true) {
            val more = onFiles {
                val batch = intake {
                    if (queue.isEmpty()) drainScheduled = false
                    takeQueued()
                }
                if (batch.isNotEmpty() && endReason == null) {
                    write(batch)
                    afterBatch()
                }
                batch.isNotEmpty()
            }
            if (!more) return
        }
    }

    private fun takeQueued(): List<Queued> {
        val batch = queue.toList()
        queue.clear()
        return batch
    }

    private fun takeAll(): List<Queued> = intake { takeQueued() }

    /**
     * Writes [batch] in order; a failure or a storage stop ends the recording and the rest is not
     * written. The fsync and the free-space check stay on time between records, also in a long backlog.
     */
    private fun write(batch: List<Queued>) {
        if (batch.isEmpty()) return
        val begun = nowMicros()
        for (q in batch) {
            if (endReason != null || killedForTest) return
            stats.waited(nowMicros() - q.atMicros)
            val record = q.record
            if (record is Record.Channel) define(record.def) else append(record)
            if (endReason == null) maintain()
        }
        stats.wrote(nowMicros() - begun)
    }

    private fun define(def: ChannelDef) {
        if (channels[def.id] == def) return
        channels[def.id] = def
        append(Record.Channel(def))
        remember(def)
    }

    /** After each batch: the queue's drops, then free space and the fsync. */
    private fun afterBatch() {
        if (endReason != null) return
        reportDrops()
        if (endReason == null) maintain()
    }

    /** Free space (checked now and then) and the fsync when it is due. */
    private fun maintain() {
        val now = elapsedMs()
        if (storageExhausted(now)) {
            finish(STORAGE_FULL)
        } else if (now - lastSyncMs >= config.syncIntervalMs) {
            syncOrFail()
        }
    }

    private fun reportDrops() {
        val d = intake { dropped }
        if (d != reportedDrops) {
            reportedDrops = d
            append(Record.Event(RecorderEvent(nowMicros(), EventKinds.DROPPED_SAMPLES, d.toString())))
        }
    }

    /** A periodic fsync; a failure ends the recording, which then waits for recovery. */
    @Suppress("TooGenericExceptionCaught") // Any failure must still end the recording, keeping what is on disk.
    private fun syncOrFail() {
        try {
            sync()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** Writes [record], rotating the chunk first when it is big or old enough; a failure ends the recording. */
    @Suppress("TooGenericExceptionCaught") // Any failure must still leave the data on disk and end the recording.
    private fun append(record: Record) {
        try {
            val due = chunkBytes >= config.chunkMaxBytes || elapsedMs() - chunkOpenedMs >= config.chunkMaxAgeMs
            if (rotate && due) {
                closeChunk()
                openChunk()
            }
            writeFrame(record)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun writeFrame(record: Record) {
        val delay = writeDelayMicrosForTest
        if (delay > 0) usleep(delay.toUInt())
        val bytes = Frames.encode(record)
        PosixFiles.writeAll(fd, bytes, path)
        chunkBytes += bytes.size
        dirty = true
    }

    private fun openChunk() {
        index++
        path = PosixFiles.join(dir, SessionFileNames.chunkName(index))
        fd = PosixFiles.openAppend(path)
        chunkBytes = PosixFiles.size(path)
        chunkOpenedMs = elapsedMs()
        PosixFiles.syncDir(dir)
        // Each chunk starts with the full channel table, so any surviving chunk can be read on its own.
        channels.values.forEach { writeFrame(Record.Channel(it)) }
        sync()
    }

    private fun sync() {
        if (dirty && fd >= 0) {
            PosixFiles.sync(fd, path)
            syncs++
        }
        dirty = false
        lastSyncMs = elapsedMs()
    }

    private fun closeChunk() {
        if (fd < 0) return
        try {
            sync()
        } finally {
            platform.posix.close(fd)
            fd = -1
        }
    }

    /**
     * Adds [def] to the metadata's channels and rewrites it: chunks do not store provenance, so the
     * metadata keeps it (FBBD-174). A failure here is not worth the recording; provenance is then
     * derived from the channel's source when the session is read.
     */
    private fun remember(def: ChannelDef) {
        val list = meta.channels.toMutableList()
        val at = list.indexOfFirst { it.id == def.id }
        if (at >= 0) list[at] = def else list += def
        if (list == meta.channels) return
        meta = meta.copy(channels = list)
        try {
            val bytes = writeProperties(metaEntries(id, meta))
            PosixFiles.writeAtomically(PosixFiles.join(dir, SessionFileNames.META), bytes)
        } catch (_: FileError) {
            // Derived on load instead.
        }
    }

    /** Checks free space now and then. Warns once when low; true when the recording must stop. */
    private fun storageExhausted(now: Long): Boolean {
        val last = lastStorageCheckMs
        if (last != null && now - last < config.storageCheckIntervalMs) return false
        lastStorageCheckMs = now
        val free = PosixFiles.usableBytes(dir)
        // From here on every write adds exactly its own frame: a rotation's channel table would eat
        // the space kept for the end record.
        if (free < config.storageStopBytes) rotate = false
        if (free < config.storageWarnBytes && !storageWarned) {
            storageWarned = true
            append(Record.Event(RecorderEvent(nowMicros(), EventKinds.STORAGE_LOW, free.toString())))
            onStorageLow?.invoke(free)
        }
        if (free < config.storageStopBytes) {
            append(Record.Event(RecorderEvent(nowMicros(), EventKinds.STORAGE_FULL, free.toString())))
        }
        return free < config.storageStopBytes && endReason == null
    }

    /**
     * Writes the end. Takes no more records; on a storage stop what still waits is counted as dropped,
     * keeping the space left for the end record.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure must still end the recording, keeping what is on disk.
    private fun finish(reason: String) {
        try {
            intake {
                accepting = false
                dropped += queue.size
                queue.clear()
            }
            reportDrops()
            val end = nowMicros()
            writeFrame(Record.End(end, reason))
            closeChunk()
            PosixFiles.writeAtomically(
                PosixFiles.join(dir, SessionFileNames.FINISHED),
                writeProperties(finishedEntries(reason, end, intake { dropped })),
            )
            end(reason)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** Disk error or similar: keeps whatever is on disk; recovery picks the session up at the next start. */
    private fun fail(t: Throwable) {
        if (endReason != null) return
        error = t
        intake {
            accepting = false
            queue.clear()
        }
        if (fd >= 0) {
            platform.posix.close(fd)
            fd = -1
        }
        end(ERROR)
    }

    private fun end(reason: String) {
        endReason = reason
        if (lockFd >= 0) {
            PosixFiles.unlock(lockFd)
            lockFd = -1
        }
        onEnded?.invoke(reason)
    }

    private companion object {
        const val STORAGE_FULL = "storage_full"
        const val ERROR = "error"
    }
}

/** How the writer kept up (FBBD-350 R2), under the recording's file lock except [waiting]'s own field. */
private class WriterStats {
    @Volatile
    private var mostWaiting = 0
    private var longestWaitMicros = 0L
    private var longestBatchMicros = 0L

    /** [n] records wait now; called under the intake lock. */
    fun waiting(n: Int) {
        if (n > mostWaiting) mostWaiting = n
    }

    fun waited(micros: Long) {
        if (micros > longestWaitMicros) longestWaitMicros = micros
    }

    fun wrote(micros: Long) {
        if (micros > longestBatchMicros) longestBatchMicros = micros
    }

    fun text(dropped: Long): String =
        "writer: max_wait_ms=${longestWaitMicros / MicrosPerMilli} " +
            "max_write_ms=${longestBatchMicros / MicrosPerMilli} max_queue=$mostWaiting dropped=$dropped"
}
