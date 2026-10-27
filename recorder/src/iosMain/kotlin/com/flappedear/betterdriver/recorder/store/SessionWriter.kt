package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SessionInfo

private const val DefaultChunkBytes = 8L shl 20
private const val BufferBytes = 64 * 1024

/**
 * Writes a whole session at once into [store], for a session made from a file (an import,
 * FBBD-331) rather than recorded: the same directory, metadata, chunk files and finished.properties
 * as [com.flappedear.betterdriver.recorder.Recording] and the Android ChunkWriter, so it reads back
 * with [SessionStore.read] and travels to Android unchanged. Chunks rotate by size only, each
 * starting with the full channel table. Not thread-safe: one thread writes.
 *
 * The session's lock is held from creation until [writeFinished] or [release], so the session
 * never lists as interrupted while it is being written.
 */
class SessionWriter(store: SessionStore, info: SessionInfo, private val chunkMaxBytes: Long = DefaultChunkBytes) {
    val id: String
    val dir: String
    private var lockFd: Int
    private val channels = LinkedHashMap<Int, ChannelDef>()
    private var index = 0
    private var fd = -1
    private var path = ""
    private var chunkBytes = 0L
    private val buffer = ByteArray(BufferBytes)
    private var buffered = 0

    init {
        val (newId, newDir, lock) = store.createSession(info)
        id = newId
        dir = newDir
        lockFd = lock
    }

    /** Opens the first chunk with [initialChannels] as its channel table. */
    fun open(initialChannels: Collection<ChannelDef>) {
        initialChannels.forEach { channels[it.id] = it }
        openChunk()
    }

    /** Appends [record]; a channel definition already in the table is not written again. */
    fun write(record: Record) {
        if (record is Record.Channel) {
            if (channels[record.def.id] == record.def) return
            channels[record.def.id] = record.def
        }
        if (chunkBytes >= chunkMaxBytes) {
            close()
            openChunk()
        }
        writeFrame(record)
    }

    /** Writes the metadata again, as [info]. */
    fun writeMeta(info: SessionInfo) {
        PosixFiles.writeAtomically(PosixFiles.join(dir, SessionFileNames.META), writeProperties(metaEntries(id, info)))
    }

    /** Writes buffered frames, fsyncs and closes the current chunk. */
    fun close() {
        if (fd < 0) return
        try {
            flush()
            PosixFiles.sync(fd, path)
        } finally {
            platform.posix.close(fd)
            fd = -1
        }
    }

    /** After [close]: finished.properties for a clean end at [endMicros], then the lock goes. */
    fun writeFinished(reason: String, endMicros: Long) {
        PosixFiles.writeAtomically(
            PosixFiles.join(dir, SessionFileNames.FINISHED),
            writeProperties(finishedEntries(reason, endMicros, 0)),
        )
        release()
    }

    /** Closes the chunk without syncing and lets go of the lock; for a failed write, whose files are dropped. */
    fun release() {
        if (fd >= 0) platform.posix.close(fd)
        fd = -1
        buffered = 0
        if (lockFd >= 0) PosixFiles.unlock(lockFd)
        lockFd = -1
    }

    private fun writeFrame(record: Record) {
        val bytes = Frames.encode(record)
        if (buffered + bytes.size > buffer.size) flush()
        if (bytes.size > buffer.size) {
            PosixFiles.writeAll(fd, bytes, path)
        } else {
            bytes.copyInto(buffer, buffered)
            buffered += bytes.size
        }
        chunkBytes += bytes.size
    }

    private fun flush() {
        if (buffered == 0) return
        PosixFiles.writeAll(fd, buffer.copyOf(buffered), path)
        buffered = 0
    }

    private fun openChunk() {
        index++
        path = PosixFiles.join(dir, SessionFileNames.chunkName(index))
        fd = PosixFiles.openAppend(path)
        chunkBytes = PosixFiles.size(path)
        PosixFiles.syncDir(dir)
        channels.values.forEach { writeFrame(Record.Channel(it)) }
    }
}
