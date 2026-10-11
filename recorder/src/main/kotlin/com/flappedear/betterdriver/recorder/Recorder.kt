package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val NanosPerMicro = 1_000L
private const val MaxSyncIntervalMs = 1_000L
private const val Mib = 1L shl 20
private const val DefaultFlushMs = 100L
private const val DefaultSyncMs = 500L
private const val DefaultChunkMib = 8L
private const val DefaultChunkAgeMs = 60_000L
private const val DefaultWarnMib = 200L
private const val DefaultStopMib = 50L
private const val DefaultStorageCheckMs = 2_000L
private const val DefaultQueueCapacity = 100_000
private const val DefaultListenerQueueCapacity = 1_024
private const val StopTimeoutMs = 10_000L
private const val StopOfferMs = 100L

data class RecorderConfig(
    /**
     * Written data is handed to the OS whenever the write queue is empty and at least this often,
     * so killing the process loses (almost) nothing.
     */
    val flushIntervalMs: Long = DefaultFlushMs,
    /** Flushed data is fsynced at least this often, so a power loss loses at most about this much (FBBD-70). */
    val syncIntervalMs: Long = DefaultSyncMs,
    val chunkMaxBytes: Long = DefaultChunkMib * Mib,
    val chunkMaxAgeMs: Long = DefaultChunkAgeMs,
    /** Below this much free space the recorder warns. */
    val storageWarnBytes: Long = DefaultWarnMib * Mib,
    /** Below this much free space the recorder stops cleanly, keeping what it has. */
    val storageStopBytes: Long = DefaultStopMib * Mib,
    val storageCheckIntervalMs: Long = DefaultStorageCheckMs,
    val queueCapacity: Int = DefaultQueueCapacity,
    val listenerQueueCapacity: Int = DefaultListenerQueueCapacity,
) {
    init {
        require(syncIntervalMs in 1..MaxSyncIntervalMs) {
            "syncIntervalMs must be 1..1000 to meet the 1 s loss limit"
        }
        require(flushIntervalMs in 1..syncIntervalMs) { "flushIntervalMs must be 1..syncIntervalMs" }
    }
}

fun interface StorageProbe {
    fun usableBytes(dir: File): Long

    companion object {
        val DEFAULT = StorageProbe { it.usableSpace }
    }
}

sealed interface RecorderStatus {
    data class StorageLow(val usableBytes: Long) : RecorderStatus
    data class Stopped(val reason: String, val error: Throwable? = null) : RecorderStatus
}

/**
 * Crash-safe session recorder. Depends only on data sources and the session store (FBBD-69).
 * Samples are appended to chunk files as they arrive; nothing waits for a final save (FBBD-70).
 */
class Recorder(
    private val store: SessionStore,
    private val config: RecorderConfig = RecorderConfig(),
    private val storage: StorageProbe = StorageProbe.DEFAULT,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Suppress("TooGenericExceptionCaught") // Rethrown after releasing the lock.
    fun start(info: SessionInfo): Recording {
        var lock: SessionFiles.SessionLock? = null
        try {
            val (id, dir) = store.createSession(info) { lock = SessionFiles.lock(it) }
            return Recording(id, dir, info, config, storage, nanoTime).also { it.begin(checkNotNull(lock)) }
        } catch (e: Exception) {
            // Startup failed: do not leave the session locked (it would look like a live recording).
            lock?.release()
            throw e
        }
    }
}

internal sealed interface Cmd {
    data class Write(val record: Record) : Cmd
    data class Stop(val reason: String) : Cmd
}

/** One running recording. Data sources push into it through [SampleSink]. */
class Recording internal constructor(
    val id: String,
    val dir: File,
    val info: SessionInfo,
    private val config: RecorderConfig,
    private val storage: StorageProbe,
    private val nanoTime: () -> Long,
) : SampleSink {

    private val startNanos = nanoTime()
    private val queue = LinkedBlockingQueue<Cmd>(config.queueCapacity)
    private val pendingChannels = ConcurrentLinkedQueue<ChannelDef>()
    private val stopping = AtomicBoolean(false)

    /**
     * Orders submissions against the stop: once [stopIntake] returns, no accepted record can still be
     * on its way into the queue, so none can land behind the stop command.
     */
    private val intake = ReentrantReadWriteLock()
    private val stopped = CountDownLatch(1)
    private val dropped = AtomicLong()
    private val sources = Sources()

    /** Live consumers. They can fail or stall without affecting the recording. */
    val listeners = ListenerHub(id, config.listenerQueueCapacity) { error ->
        enqueue(Record.Event(RecorderEvent(nowMicros(), EventKinds.LISTENER_ERROR, error)))
    }

    @Volatile
    var finalStatus: RecorderStatus.Stopped? = null
        private set

    /** Samples dropped because the write queue was full. Expected to stay 0. */
    val droppedCount: Long get() = dropped.get()

    val isStopped: Boolean get() = stopped.count == 0L

    internal fun begin(lock: SessionFiles.SessionLock) {
        val input = WriterInput(queue, pendingChannels, dropped, nanoTime, ::nowMicros) { stopIntake() }
        val writer = RecordingWriter(dir, config, storage, input, listeners)
        Thread({
            val status = writer.run(info.channels)
            stopIntake()
            sources.stopAll()
            lock.release()
            finalStatus = status
            listeners.dispatch { it.onStatus(status) }
            listeners.shutdown()
            stopped.countDown()
        }, "recorder-writer-$id").start()
    }

    override fun nowMicros(): Long = (nanoTime() - startNanos) / NanosPerMicro

    /** Definitions bypass the bounded queue, so a full queue can never lose one. */
    override fun defineChannel(def: ChannelDef) = intake.read {
        if (!stopping.get()) pendingChannels.add(def)
    }

    override fun submit(set: SampleSet) = enqueue(Record.Samples(set))

    override fun event(kind: String, text: String) = enqueue(Record.Event(RecorderEvent(nowMicros(), kind, text)))

    /**
     * Starts [source] pushing into this recording. A source that throws on start is logged, not fatal.
     * Returns false, without starting it, once the recording is stopping.
     */
    @Suppress("TooGenericExceptionCaught") // Isolation: no source failure may stop the recording.
    fun attach(source: DataSource): Boolean {
        val added = intake.read { !stopping.get() && sources.add(source) }
        if (!added) return false
        try {
            source.start(this)
        } catch (e: Exception) {
            event(EventKinds.SOURCE_ERROR, "${source.name}: start failed: $e")
        }
        // A stop that ran while the source was starting may have missed it.
        if (stopping.get()) sources.stopOne(source)
        return true
    }

    /**
     * Stops sources, writes the end record, syncs and marks the session finished.
     * Returns true once all of that is done, or false if it did not finish within [timeoutMs].
     */
    fun stop(reason: String = "user", timeoutMs: Long = StopTimeoutMs): Boolean {
        if (stopIntake()) {
            // Runs on its own thread so a source that blocks in stop() cannot make this call exceed timeoutMs.
            Thread({
                sources.stopAll()
                // Sources are stopped, so the writer frees space; never discard accepted records to make room.
                while (!isStopped && !queue.offer(Cmd.Stop(reason), StopOfferMs, TimeUnit.MILLISECONDS)) Unit
            }, "recorder-stop-$id").apply {
                isDaemon = true
                start()
            }
        }
        return awaitStopped(timeoutMs)
    }

    fun awaitStopped(timeoutMs: Long = StopTimeoutMs): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun enqueue(record: Record) = intake.read {
        if (!stopping.get() && !queue.offer(Cmd.Write(record))) dropped.incrementAndGet()
    }

    /** Stops accepting records. True for the call that actually stopped intake. */
    private fun stopIntake(): Boolean = intake.write { stopping.compareAndSet(false, true) }
}

/** Marks a camera starting. The event time is the video's offset to session time. */
fun SampleSink.videoStarted(camera: String, file: String) = event(EventKinds.VIDEO_START, "camera=$camera file=$file")

fun SampleSink.videoStopped(camera: String, file: String) = event(EventKinds.VIDEO_STOP, "camera=$camera file=$file")

private class Sources {
    private val list = mutableListOf<DataSource>()

    fun add(source: DataSource): Boolean = synchronized(list) { list.add(source) }

    @Suppress("TooGenericExceptionCaught") // A source failing to stop must not affect the recording.
    fun stopOne(source: DataSource) {
        try {
            source.stop()
        } catch (_: Exception) {
            // Nothing to do: the source is being dropped anyway.
        }
    }

    @Suppress("TooGenericExceptionCaught") // A source failing to stop must not block the others.
    fun stopAll() = synchronized(list) {
        list.forEach {
            try {
                it.stop()
            } catch (_: Exception) {
                // Nothing to do: the source is being dropped anyway.
            }
        }
    }
}
