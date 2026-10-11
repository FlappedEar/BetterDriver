package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.ChunkWriter
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import java.io.File
import java.util.Properties
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val NanosPerMilli = 1_000_000L

internal class WriterInput(
    val queue: BlockingQueue<Cmd>,
    val pendingChannels: Queue<ChannelDef>,
    val dropped: AtomicLong,
    val nanoTime: () -> Long,
    val nowMicros: () -> Long,
    /** Stops the recording accepting new records. */
    val stopIntake: () -> Unit,
)

/** The recording's writing thread: drains the queue into chunk files and watches free space. */
internal class RecordingWriter(
    private val dir: File,
    private val config: RecorderConfig,
    private val storage: StorageProbe,
    input: WriterInput,
    private val listeners: ListenerHub,
) {
    private val queue = input.queue
    private val pendingChannels = input.pendingChannels
    private val dropped = input.dropped
    private val nanoTime = input.nanoTime
    private val nowMicros = input.nowMicros
    private val stopIntake = input.stopIntake
    private val chunks = ChunkWriter(dir, config.chunkMaxBytes, config.chunkMaxAgeMs, config.syncIntervalMs, nanoTime)
    private var lastFlushNanos = 0L
    private var lastStorageCheckNanos: Long? = null
    private var storageWarned = false
    private var reportedDrops = 0L

    /** Runs until stopped, storage runs out or a write fails. Returns how it ended. */
    @Suppress("TooGenericExceptionCaught") // Any failure must still end with the data on disk.
    fun run(initialChannels: List<ChannelDef>): RecorderStatus.Stopped = try {
        chunks.open(initialChannels)
        val reason = loop()
        finish(reason)
        RecorderStatus.Stopped(reason)
    } catch (t: Throwable) {
        // Disk error or similar: keep whatever is on disk; recovery picks the session up later.
        try {
            chunks.close()
        } catch (_: Exception) {
            // Already failing; the data written so far is what we keep.
        }
        RecorderStatus.Stopped("error", t)
    }

    private fun loop(): String {
        var reason: String? = null
        while (reason == null) {
            val cmd = queue.poll(config.flushIntervalMs, TimeUnit.MILLISECONDS)
            // Definitions first: a source defines a channel before it submits samples for it.
            writePendingChannels()
            when (cmd) {
                is Cmd.Stop -> reason = cmd.reason
                is Cmd.Write -> write(cmd.record)
                null -> Unit
            }
            if (reason == null) {
                // Hand data to the OS when the queue drains, and at least every flush interval under load.
                // The sync thread makes it durable.
                if (queue.isEmpty() || nanoTime() - lastFlushNanos >= config.flushIntervalMs * NanosPerMilli) flush()
                // Checked on every pass, so a queue that never empties cannot skip the storage stop.
                if (storageExhausted()) reason = STORAGE_FULL
            }
        }
        if (reason == STORAGE_FULL) drainAcceptedWrites()
        return reason
    }

    /**
     * Stops intake, then writes what was already accepted, so a storage stop normally loses nothing.
     * Writing stops at half the stop margin, keeping the rest for the end record: a backlog too big for
     * the space left is counted as dropped (and logged) rather than filling the disk.
     */
    private fun drainAcceptedWrites() {
        stopIntake()
        writePendingChannels()
        val free = runCatching { storage.usableBytes(dir) }.getOrDefault(Long.MAX_VALUE)
        var budget = free - config.storageStopBytes / 2
        var cmd = queue.poll()
        while (cmd != null) {
            if (cmd is Cmd.Write) {
                // Sized before writing, so a frame larger than what is left is never started.
                val size = Frames.encode(cmd.record).size.toLong()
                if (size <= budget) budget -= write(cmd.record) else dropped.incrementAndGet()
            }
            cmd = queue.poll()
        }
    }

    private fun writePendingChannels() {
        var def = pendingChannels.poll()
        while (def != null) {
            write(Record.Channel(def))
            def = pendingChannels.poll()
        }
    }

    private fun finish(reason: String) {
        writePendingChannels()
        reportDrops()
        chunks.write(Record.End(nowMicros(), reason))
        chunks.close()
        SessionFiles.writeAtomically(
            File(dir, SessionFiles.FINISHED),
            Properties().apply {
                setProperty("reason", reason)
                setProperty("endMicros", nowMicros().toString())
                setProperty("droppedSamples", dropped.get().toString())
            },
        )
    }

    /** Returns the bytes written. */
    private fun write(record: Record): Long {
        val written = chunks.write(record)
        when (record) {
            is Record.Channel -> listeners.dispatch { it.onChannel(record.def) }
            is Record.Samples -> listeners.dispatch { it.onSamples(record.set) }
            // Listener errors are not sent back to listeners: a listener failing on them would loop.
            is Record.Event -> if (record.event.kind != EventKinds.LISTENER_ERROR) {
                listeners.dispatch { it.onEvent(record.event) }
            }
            is Record.End -> Unit
        }
        return written
    }

    private fun flush() {
        reportDrops()
        chunks.flush()
        lastFlushNanos = nanoTime()
    }

    private fun reportDrops() {
        val d = dropped.get()
        if (d != reportedDrops) {
            reportedDrops = d
            chunks.write(Record.Event(RecorderEvent(nowMicros(), EventKinds.DROPPED_SAMPLES, d.toString())))
        }
    }

    /** Checks free space now and then. Warns once when low; true when the recording must stop. */
    private fun storageExhausted(): Boolean {
        val now = nanoTime()
        val last = lastStorageCheckNanos
        if (last != null && now - last < config.storageCheckIntervalMs * NanosPerMilli) return false
        lastStorageCheckNanos = now
        val free = runCatching { storage.usableBytes(dir) }.getOrDefault(Long.MAX_VALUE)
        if (free < config.storageWarnBytes && !storageWarned) {
            storageWarned = true
            chunks.write(Record.Event(RecorderEvent(nowMicros(), EventKinds.STORAGE_LOW, free.toString())))
            listeners.dispatch { it.onStatus(RecorderStatus.StorageLow(free)) }
        }
        if (free < config.storageStopBytes) {
            chunks.write(Record.Event(RecorderEvent(nowMicros(), EventKinds.STORAGE_FULL, free.toString())))
        }
        return free < config.storageStopBytes
    }

    private companion object {
        const val STORAGE_FULL = "storage_full"
    }
}
