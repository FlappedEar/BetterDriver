package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoEventText
import com.flappedear.betterdriver.recorder.video.VideoSink
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
        // One snapshot for both the metadata and the writer thread, so a caller's later changes reach neither.
        val info = info.copy(devices = info.devices.toList(), channels = info.channels.toList())
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
// The sink, source and stop API of one recording; splitting it would hide the ordering rules.
@Suppress("TooManyFunctions")
class Recording internal constructor(
    val id: String,
    val dir: File,
    val info: SessionInfo,
    private val config: RecorderConfig,
    private val storage: StorageProbe,
    private val nanoTime: () -> Long,
) : SampleSink {

    /**
     * [System.nanoTime] at session time 0. On Android that clock is CLOCK_MONOTONIC, shared by all
     * processes, so another process can stamp its reports with it: session micros = (nanos - this) / 1000.
     */
    val startNanos = nanoTime()
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
            val status = writer.run(info)
            stopIntake()
            lock.release()
            finalStatus = status
            listeners.dispatch { it.onStatus(status) }
            listeners.shutdown()
            stopped.countDown()
            // Last, so a source that blocks in stop() cannot hold up the finished recording.
            // Sources a user stop already stopped are not stopped again.
            sources.stopAll()
        }, "recorder-writer-$id").start()
    }

    override fun nowMicros(): Long = (nanoTime() - startNanos) / NanosPerMicro

    /** Definitions bypass the bounded queue, so a full queue can never lose one. */
    override fun defineChannel(def: ChannelDef) = intake.read {
        if (!stopping.get()) {
            pendingChannels.add(def)
            listeners.dispatch { it.onChannel(def) }
        }
    }

    /** The values are copied here: the writer reads them later, and the source may reuse its list. */
    override fun submit(set: SampleSet) = enqueue(Record.Samples(set.copy(values = set.values.toList())))

    override fun event(kind: String, text: String) = enqueue(Record.Event(RecorderEvent(nowMicros(), kind, text)))

    /** An event that happened at session time [tMicros], such as a camera report stamped in another process. */
    fun eventAt(tMicros: Long, kind: String, text: String) = enqueue(Record.Event(RecorderEvent(tMicros, kind, text)))

    /**
     * Starts [source] pushing into this recording. A source that throws on start is logged, not fatal.
     * Returns false, without starting it, once the recording is stopping.
     */
    @Suppress("TooGenericExceptionCaught") // Isolation: no source failure may stop the recording.
    fun attach(source: DataSource): Boolean {
        val added = intake.read { !stopping.get() && sources.beginStart(source) }
        if (!added) return false
        try {
            source.start(this)
        } catch (e: Exception) {
            event(EventKinds.SOURCE_ERROR, "${source.name}: start failed: $e")
        } finally {
            // Stops it now if a stop ran while it was starting; otherwise the next stopAll does.
            sources.endStart(source)
        }
        return true
    }

    /**
     * Stops sources, writes the end record, syncs and marks the session finished.
     * Returns true once all of that is done, or false if it did not finish within [timeoutMs].
     */
    fun stop(reason: String = "user", timeoutMs: Long = StopTimeoutMs): Boolean {
        if (stopIntake()) {
            // Intake is closed, so the queue only drains: the stop marker needs no source to stop first.
            // Each runs on its own thread, so a source that blocks in stop() can neither keep the session
            // from finishing nor make this call exceed timeoutMs.
            daemon("recorder-stop-$id") {
                // Never discard accepted records to make room.
                while (!isStopped && !queue.offer(Cmd.Stop(reason), StopOfferMs, TimeUnit.MILLISECONDS)) Unit
            }
            daemon("recorder-sources-$id") { sources.stopAll() }
        }
        return awaitStopped(timeoutMs)
    }

    fun awaitStopped(timeoutMs: Long = StopTimeoutMs): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Live consumers get each accepted record here, as it arrives, not after the writer has put it on
     * disk: on Arek's 2026-10-17 drive a disk write stalled for 2.2 s (a 4K file ending, storage nearly
     * full), and the track screen showed its no-GNSS banner while every fix was on time.
     */
    private fun enqueue(record: Record) = intake.read {
        if (stopping.get()) return@read
        if (queue.offer(Cmd.Write(record))) live(record) else dropped.incrementAndGet()
    }

    private fun live(record: Record) {
        when (record) {
            is Record.Samples -> listeners.dispatch { it.onSamples(record.set) }
            // Listener errors are not sent back to listeners: a listener failing on them would loop.
            is Record.Event -> if (record.event.kind != EventKinds.LISTENER_ERROR) {
                listeners.dispatch { it.onEvent(record.event) }
            }
            is Record.Channel, is Record.End -> Unit
        }
    }

    /** Stops accepting records. True for the call that actually stopped intake. */
    private fun stopIntake(): Boolean = intake.write { stopping.compareAndSet(false, true) }
}

private fun daemon(name: String, body: () -> Unit) = Thread(body, name).apply {
    isDaemon = true
    start()
}

/** Marks a camera starting. The event time is the video's offset to session time. */
fun SampleSink.videoStarted(camera: String, file: String) = event(EventKinds.VIDEO_START, "camera=$camera file=$file")

fun SampleSink.videoStopped(camera: String, file: String) = event(EventKinds.VIDEO_STOP, "camera=$camera file=$file")

/**
 * Logs what a camera reports, at the times it reports. [onStatus] receives status for the live UI.
 * A camera in the recorder's own process can use this directly; one in another process forwards
 * its reports and the recorder calls the same methods.
 */
class RecordingVideoSink(
    private val recording: Recording,
    private val camera: String,
    private val onStatus: (camera: String, text: String, ok: Boolean) -> Unit = { _, _, _ -> },
) : VideoSink {
    override fun nowMicros() = recording.nowMicros()

    override fun started(file: String, startMicros: Long, fps: Int?) = recording.eventAt(
        startMicros,
        EventKinds.VIDEO_START,
        VideoEventText.format(VideoEventText.CAMERA to camera, VideoEventText.FILE to file) +
            fps?.let { " ${VideoEventText.FPS}=$it" }.orEmpty(),
    )

    override fun synced(file: String, atMicros: Long, mediaMicros: Long, frameMicros: Long?) = recording.eventAt(
        atMicros,
        EventKinds.VIDEO_SYNC,
        VideoEventText.format(
            VideoEventText.CAMERA to camera,
            VideoEventText.FILE to file,
            VideoEventText.MEDIA_US to mediaMicros,
        ) + frameMicros?.let { " ${VideoEventText.FRAME_US}=$it" }.orEmpty(),
    )

    override fun stopped(file: String, atMicros: Long, reason: String) = recording.eventAt(
        atMicros,
        EventKinds.VIDEO_STOP,
        VideoEventText.format(
            VideoEventText.CAMERA to camera,
            VideoEventText.FILE to file,
            VideoEventText.REASON to reason,
        ),
    )

    override fun status(text: String, ok: Boolean) = onStatus(camera, text, ok)

    override fun thermal(atMicros: Long, status: Int, headroom: Float?, ignored: Boolean) =
        recording.eventAt(atMicros, EventKinds.THERMAL_STATUS, thermalText(status, headroom, ignored))
}

/** The text of a [EventKinds.THERMAL_STATUS] event; " heat=ignored" during a heat stress test (FBBD-231). */
fun thermalText(status: Int, headroom: Float?, ignored: Boolean = false): String =
    "status=$status headroom=${headroom ?: "n/a"}" + if (ignored) " heat=ignored" else ""

/**
 * The attached sources. Each source is stopped exactly once: [DataSource.stop] need not be idempotent.
 * A source still starting when [stopAll] runs is stopped by [endStart] once its start returns.
 */
private class Sources {
    private val starting = mutableListOf<DataSource>()
    private val started = mutableListOf<DataSource>()
    private var closed = false

    /** False once [stopAll] has run. */
    fun beginStart(source: DataSource): Boolean = synchronized(this) {
        if (!closed) starting.add(source)
        !closed
    }

    fun endStart(source: DataSource) {
        val stopNow = synchronized(this) {
            starting.remove(source)
            if (!closed) started.add(source)
            closed
        }
        if (stopNow) stopQuietly(source)
    }

    fun stopAll() {
        val toStop = synchronized(this) {
            closed = true
            started.toList().also { started.clear() }
        }
        toStop.forEach(::stopQuietly)
    }

    @Suppress("TooGenericExceptionCaught") // A source failing to stop must not affect the recording or the others.
    private fun stopQuietly(source: DataSource) {
        try {
            source.stop()
        } catch (_: Exception) {
            // Nothing to do: the source is being dropped anyway.
        }
    }
}
