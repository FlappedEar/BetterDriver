package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

private const val NanosPerMilli = 1_000_000L
private const val BufferBytes = 64 * 1024

/**
 * Appends framed records to a session's chunk files (FBBD-70).
 *
 * - [write] buffers; [flush] hands the buffer to the OS, which survives a process kill.
 * - A background thread fsyncs flushed data every [syncIntervalMs], which survives a power loss.
 *   fsync runs off the writing thread so a slow disk never holds up writing.
 * - Chunks rotate by size and age, and each chunk starts with the full channel table so any
 *   surviving chunk can be read on its own.
 *
 * Not thread-safe: one writing thread calls [write], [flush] and [close].
 */
class ChunkWriter(
    private val dir: File,
    private val maxBytes: Long,
    private val maxAgeMs: Long,
    private val syncIntervalMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val channels = LinkedHashMap<Int, ChannelDef>()
    private var index = 0
    private var fos: FileOutputStream? = null
    private var out: BufferedOutputStream? = null
    private var bytes = 0L
    private var openedNanos = 0L
    private var dirty = false

    private val syncLock = Any()

    @Volatile
    private var syncFd: FileDescriptor? = null

    @Volatile
    private var syncPending = false

    @Volatile
    private var closed = false

    private val syncThread = Thread(::syncLoop, "recorder-sync-${dir.name}").apply { isDaemon = true }

    /** Opens the first chunk with [initialChannels] as its channel table. */
    fun open(initialChannels: Collection<ChannelDef>) {
        initialChannels.forEach { channels[it.id] = it }
        openChunk()
        syncThread.start()
    }

    /** Appends [record]. A channel definition already in the table is not written again. */
    fun write(record: Record) {
        if (record is Record.Channel) {
            if (channels[record.def.id] == record.def) return
            channels[record.def.id] = record.def
        }
        val ageMs = (nanoTime() - openedNanos) / NanosPerMilli
        if (bytes >= maxBytes || ageMs >= maxAgeMs) {
            closeChunk()
            openChunk()
        }
        writeFrame(record)
    }

    /** Hands buffered frames to the OS and asks the sync thread for an fsync. */
    fun flush() {
        val o = out ?: return
        if (dirty) {
            o.flush()
            dirty = false
            syncPending = true
        }
    }

    /** Flushes, fsyncs and closes the current chunk, and stops the sync thread. */
    fun close() {
        closed = true
        syncThread.interrupt()
        closeChunk()
    }

    private fun writeFrame(record: Record) {
        val b = Frames.encode(record)
        checkNotNull(out) { "chunk not open" }.write(b)
        bytes += b.size
        dirty = true
    }

    private fun openChunk() {
        index++
        val f = File(dir, SessionFiles.chunkName(index))
        val stream = FileOutputStream(f, true)
        fos = stream
        out = BufferedOutputStream(stream, BufferBytes)
        syncFd = stream.fd
        bytes = f.length()
        openedNanos = nanoTime()
        SessionFiles.syncDir(dir)
        channels.values.forEach { writeFrame(Record.Channel(it)) }
        flush()
    }

    private fun closeChunk() {
        val o = out ?: return
        try {
            o.flush()
            synchronized(syncLock) {
                syncFd = null
                fos?.fd?.sync()
            }
        } finally {
            o.close()
            out = null
            fos = null
        }
    }

    private fun syncLoop() {
        while (!closed) {
            try {
                Thread.sleep(syncIntervalMs)
            } catch (_: InterruptedException) {
                continue
            }
            if (syncPending) {
                syncPending = false
                synchronized(syncLock) {
                    try {
                        syncFd?.sync()
                    } catch (_: IOException) {
                        // A failing disk shows up as a write error on the writing thread.
                    }
                }
            }
        }
    }
}
