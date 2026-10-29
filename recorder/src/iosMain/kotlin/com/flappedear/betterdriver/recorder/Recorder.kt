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
import platform.Foundation.NSRecursiveLock
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import kotlin.time.TimeSource

private const val Mib = 1L shl 20
private const val DefaultSyncMs = 500L
private const val DefaultChunkMib = 8L
private const val DefaultChunkAgeMs = 60_000L
private const val DefaultWarnMib = 200L
private const val DefaultStopMib = 50L
private const val DefaultStorageCheckMs = 2_000L
private const val MaxSyncIntervalMs = 1_000L
private const val NanosPerMilli = 1_000_000L

/** As RecorderConfig on Android, without its queues: on iOS each write goes straight to the file. */
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
) {
    init {
        require(syncIntervalMs in 1..MaxSyncIntervalMs) { "syncIntervalMs must be 1..1000 to meet the 1 s loss limit" }
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
 * One running recording. Sources push into it through [SampleSink] from any thread; each call
 * appends its frame to the current chunk with write(2) before it returns, so an app crash loses
 * nothing already submitted, and the chunk is fsynced at least every [RecorderConfig.syncIntervalMs],
 * also when no more data comes (a timer on a background queue). A write or fsync failure ends the
 * recording with reason "error" and leaves the files for recovery.
 */
// The sink, stop and failure paths of one recording share its lock and file; splitting it would hide the ordering.
@Suppress("TooManyFunctions")
class Recording internal constructor(
    val id: String,
    val dir: String,
    info: SessionInfo,
    private var lockFd: Int,
    private val config: RecorderConfig,
) : SampleSink {
    private val started = TimeSource.Monotonic.markNow()
    private val lock = NSRecursiveLock()
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

    /** Why it ended, once it has: "user" or another stop reason, "storage_full" or "error". */
    var endReason: String? = null
        private set

    /** The failure behind an "error" end. */
    var error: Throwable? = null
        private set

    /** Called once, on the thread that ended it, with [endReason]. */
    var onEnded: ((String) -> Unit)? = null

    /** Called once when free space falls below [RecorderConfig.storageWarnBytes], with the bytes left. */
    var onStorageLow: ((Long) -> Unit)? = null

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
            val running = guarded {
                if (endReason == null && elapsedMs() - lastSyncMs >= config.syncIntervalMs) syncOrFail()
                endReason == null
            }
            if (running) scheduleSync()
        }
    }

    private fun elapsedMs() = started.elapsedNow().inWholeMilliseconds

    override fun nowMicros(): Long = started.elapsedNow().inWholeMicroseconds

    val isRecording: Boolean get() = guarded { endReason == null }

    override fun defineChannel(def: ChannelDef) = guarded {
        if (endReason != null || channels[def.id] == def) return@guarded
        channels[def.id] = def
        append(Record.Channel(def))
        remember(def)
    }

    override fun submit(set: SampleSet) = enqueue(Record.Samples(set.copy(values = set.values.toList())))

    override fun event(kind: String, text: String) = enqueue(Record.Event(RecorderEvent(nowMicros(), kind, text)))

    /** An event stamped by its source at [tMicros] on this recording's clock. */
    fun eventAt(tMicros: Long, kind: String, text: String) = enqueue(Record.Event(RecorderEvent(tMicros, kind, text)))

    /**
     * Ends the recording cleanly: an end record, a final fsync and finished.properties.
     * False if it had already ended.
     */
    fun stop(reason: String = "user"): Boolean = guarded {
        if (endReason != null) return@guarded false
        finish(reason)
        true
    }

    /** Tests only: whether written data still waits for its fsync. */
    internal val isDirtyForTest: Boolean get() = guarded { dirty }

    /** Tests only: closes the chunk behind the recorder's back, so its next fsync fails. */
    internal fun breakFileForTest() = guarded {
        platform.posix.close(fd)
    }

    /** Tests only: drops the files and the lock as a killed app would, without an end record. */
    internal fun abandonForTest() = guarded {
        if (fd >= 0) platform.posix.close(fd)
        fd = -1
        if (lockFd >= 0) PosixFiles.unlock(lockFd)
        lockFd = -1
        endReason = "abandoned"
    }

    private inline fun <T> guarded(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    private fun enqueue(record: Record) = guarded {
        if (endReason != null) return@guarded
        append(record)
        val now = elapsedMs()
        if (storageExhausted(now)) {
            finish(STORAGE_FULL)
        } else if (now - lastSyncMs >= config.syncIntervalMs) {
            syncOrFail()
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
        if (dirty && fd >= 0) PosixFiles.sync(fd, path)
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

    @Suppress("TooGenericExceptionCaught") // Any failure must still end the recording, keeping what is on disk.
    private fun finish(reason: String) {
        try {
            val end = nowMicros()
            writeFrame(Record.End(end, reason))
            closeChunk()
            PosixFiles.writeAtomically(
                PosixFiles.join(dir, SessionFileNames.FINISHED),
                writeProperties(finishedEntries(reason, end, 0)),
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
