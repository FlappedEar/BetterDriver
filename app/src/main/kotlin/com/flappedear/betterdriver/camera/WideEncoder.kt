package com.flappedear.betterdriver.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes the 2.7K camera stream itself, in place of MediaRecorder (FBBD-246, FBBD-248): Camera2
 * draws into [surface], a MediaCodec video encoder run at real-time priority with room above the
 * frame rate, sound from [pcm] goes through an AAC encoder, and [FileCuts] decides which MP4 each
 * sample goes to, so a full file hands over to the next at a keyframe with no frame or sound lost.
 *
 * Each encoded sample is copied and its buffer handed back to the encoder at once; a writer thread
 * puts the copies into the MP4s (FBBD-278). Until 1.6.3 the encoder's callback wrote the sample under
 * the muxer lock first, so a slow write held the encoder's buffer, and with it the camera's frames.
 * [timing] says how long each step takes.
 *
 * [events] are called on the encoder's own threads. [stop] blocks; call it off the main thread.
 * A file asks for the keyframe that starts the next one [cutMarginBytes] before its size limit.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One function per encoder, muxer and file step; test hooks.
internal class WideEncoder(
    private val target: WideTarget,
    private val pcm: PcmSource?,
    private val clockNanos: () -> Long,
    private val events: Events,
    cutMarginBytes: Long = CutMarginBytes,
    /**
     * False for the test without MP4 (FBBD-278): both encoders run as when recording, and each sample's
     * buffer goes back to its encoder at once, with no copy, writer or muxer. No file is opened.
     */
    private val mux: Boolean = true,
    /** Room for copies waiting for the writer, and how long the encoder waits for it ([WriterBacklog]). */
    backlogKib: Int = MaxBacklogKib,
    private val backlogWaitMs: Long = BacklogWaitMs,
    /** Runs on the writer thread before each sample is written: tests make the writer slow or stuck with it. */
    private val beforeWrite: (() -> Unit)? = null,
) {
    /** What the encoder reports, on its own threads. */
    interface Events {
        /** The current file is nearly full: hand the next one to [offerNext]. */
        fun nextWanted()

        /** The first file holds frames from [baseNanos] on (the camera's clock). */
        fun started(file: File, baseNanos: Long)

        /** [done] holds [mediaMicros] of video; [next] goes on from [baseNanos]. */
        fun handedOver(done: File, mediaMicros: Long, next: File, baseNanos: Long)

        /** [file] is finished on disk, or [error] says why not. */
        fun closed(file: File, stats: FileStats, error: String?)

        /** A file reached its size limit with no next file to go on in. */
        fun full()

        /** The encoder failed; recording cannot go on. */
        fun failed(error: String)
    }

    /** What went into one file. [encodeMs]: time from frame to the encoder's callback for it, p50/p95/max. */
    data class FileStats(val frames: Long, val mediaMicros: Long, val audio: Boolean, val encodeMs: String)

    private val videoThread = HandlerThread("WideVideo").apply { start() }
    private val audioThread = HandlerThread("WideAudio").apply { start() }
    private val closer = Executors.newSingleThreadExecutor { Thread(it, "WideMuxerClose") }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "WideWriter") }
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    /** Copies of sound samples, which are small: a video-sized copy for each would hold 256 KiB (audit A15). */
    private val soundPool = ConcurrentLinkedQueue<ByteBuffer>()

    /**
     * Room for copies waiting for the writer, by their capacity: the encoder waits only when it is all
     * taken, and a writer still behind after the wait ends the recording instead of growing the queue.
     */
    private val backlog = WriterBacklog(backlogKib, backlogWaitMs)
    private val videoFull = { timing.fullWait() }

    /** A write threw: samples still queued are dropped, their room given back ([writeFailed]). */
    private val writeFailure = AtomicBoolean(false)

    /** Video samples' time from encoder to MP4, logged once a minute ([SampleTiming.line]). */
    val timing = SampleTiming()
    private val lock = Any()
    private val cuts = FileCuts(target.maxFileBytes - cutMarginBytes)
    private val videoDone = CountDownLatch(1)
    private val audioDone = CountDownLatch(if (pcm == null) 0 else 1)

    private val video: MediaCodec
    private var audio: MediaCodec? = null

    /** How the video encoder was set up, for the log: codec, type and operating rate. */
    val setup: String

    /** The surface the camera draws into. */
    val surface: Surface

    @Volatile private var ending = false
    private var first: File? = null
    private var next: File? = null
    private var current: Out? = null
    private var previous: Out? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var audioWanted = pcm != null
    private val waiting = ArrayDeque<Waiting>()

    /** Sound newer than the latest video while a cut is pending: it goes to whichever file the cut puts it in. */
    private val heldSound = ArrayDeque<Waiting>()
    private var videoUs = Long.MIN_VALUE

    /** Time in writeSampleData for the video sample being written (a held one may be written with it). */
    private var writeNanos = 0L

    /** When the encoder's callback for the newest video sample began ([clockNanos]). */
    private var enteredNanos = 0L

    init {
        // A refusal leaves no encoder for the caller to stop, so the threads end here.
        val started = runCatching { videoEncoder(target, VideoCallback(), Handler(videoThread.looper)) }
            .onFailure { quitThreads() }
            .getOrThrow()
        video = started.codec
        setup = started.setup
        surface = started.surface
    }

    /** Starts the sound; the first file opens with the first keyframe the camera's frames give. */
    @Suppress("TooGenericExceptionCaught") // A microphone that cannot start only leaves the files silent.
    fun start(file: File) {
        first = file
        val source = pcm
        if (source != null) {
            try {
                source.start()
                val codec = audioEncoder(source, AudioCallback(source), Handler(audioThread.looper))
                audio = codec
                codec.start()
            } catch (e: Exception) {
                DebugLog.w(Tag, "no sound: microphone or AAC encoder refused", e)
                runCatching { source.stop() }
                audio?.release()
                audio = null
                synchronized(lock) { audioWanted = false }
                audioDone.countDown()
            }
        }
    }

    /** The file after the current one, asked for by [Events.nextWanted]; the next keyframe starts it. */
    fun offerNext(file: File) {
        synchronized(lock) { next = file }
        runCatching { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /**
     * Ends the recording: drains both encoders, closes every file and releases everything. Returns the
     * last file's stats ([Closed]); null when no file was ever opened.
     */
    fun stop(): Closed? {
        ending = true
        runCatching { video.signalEndOfInputStream() }
        runCatching { pcm?.stop() }
        val drained = videoDone.await(DrainSeconds, TimeUnit.SECONDS) and
            audioDone.await(DrainSeconds, TimeUnit.SECONDS)
        // The file is still finished: what was encoded so far plays; the log says its end is missing.
        if (!drained) DebugLog.w(Tag, "encoders not drained after $DrainSeconds s: the last frames are lost")
        val (last, unused) = synchronized(lock) {
            // Video still held for a sound format that never came is not lost with the sound.
            giveUpSound()
            // A next file that could not be opened left the full one current (cut), so it is the last.
            releaseSound(all = true)
            val c = current
            closePrevious()
            current = null
            val n = next
            next = null
            c to n
        }
        writer.shutdown()
        closer.shutdown()
        // The file handed over last may still be finishing; the end is reported only once it is.
        if (!closer.awaitTermination(CloseSeconds, TimeUnit.SECONDS)) {
            DebugLog.w(Tag, "a handed-over file was still finishing after $CloseSeconds s")
        }
        val closed = last?.let { Closed(it.file, it.stats(), finish(it)) }
        runCatching { video.stop() }
        video.release()
        audio?.let { a ->
            runCatching { a.stop() }
            a.release()
        }
        surface.release()
        quitThreads()
        unused?.delete()
        return closed
    }

    private fun quitThreads() {
        writer.shutdown()
        closer.shutdown()
        videoThread.quitSafely()
        audioThread.quitSafely()
    }

    /** The last file: [stats], and [error] when it could not be finished. */
    data class Closed(val file: File, val stats: FileStats, val error: String?)

    private fun sample(track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        val queued = !ready()
        if (queued) {
            waiting.addLast(Waiting(track, buffer.copy(info), info.copy()))
            if (soundLate()) {
                DebugLog.w(Tag, "no sound: the AAC encoder gave no format")
                audioWanted = false
            }
        }
        // Samples held for a format go first, whichever callback made both formats known.
        if (ready()) {
            while (waiting.isNotEmpty()) waiting.removeFirst().let { route(it.track, it.data, it.info) }
            if (!queued) route(track, buffer, info)
        }
    }

    private fun ready() = videoFormat != null && (audioFormat != null || !audioWanted)

    /** With no sound format yet, goes on without sound and routes the video held for it. */
    private fun giveUpSound() {
        if (audioFormat != null) return
        audioWanted = false
        if (ready()) while (waiting.isNotEmpty()) waiting.removeFirst().let { route(it.track, it.data, it.info) }
    }

    /** Video is ready but the AAC encoder has given no format for a second of it: go on without sound. */
    private fun soundLate(): Boolean {
        val videoReady = videoFormat != null
        return videoReady && audioWanted && audioFormat == null && waitedUs() > AudioWaitUs
    }

    private fun waitedUs(): Long {
        val times = waiting.filter { it.track == Track.Video }.map { it.info.presentationTimeUs }
        return if (times.isEmpty()) 0 else times.max() - times.min()
    }

    private fun route(track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track == Track.Audio) {
            holdOrRouteAudio(buffer, info)
        } else {
            routeVideo(buffer, info)
            videoUs = info.presentationTimeUs
            releaseSound(all = false)
        }
    }

    /**
     * A cut is pending: the file is due, and the keyframe that starts the next has not been seen yet,
     * whether or not the next file has arrived from the main thread.
     */
    private fun cutPending() = cuts.due

    /**
     * The sound encoder runs ahead of a loaded video encoder: sound past the latest frame waits while a
     * cut is pending, so sound after the cut's keyframe goes to the next file, not the end of this one.
     */
    private fun holdOrRouteAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val ahead = cutPending() && info.presentationTimeUs > videoUs
        if (ahead || heldSound.isNotEmpty()) {
            heldSound.addLast(Waiting(Track.Audio, buffer.copy(info), info.copy()))
            // Video that stopped coming must not hold sound for ever.
            if (heldSound.size > MaxHeldSound) heldSound.removeFirst().let { routeAudio(it.data, it.info) }
            releaseSound(all = false)
        } else {
            routeAudio(buffer, info)
        }
    }

    /** Routes held sound the video has caught up with; [all] of it once there is no cut to wait for. */
    private fun releaseSound(all: Boolean) {
        while (heldSound.isNotEmpty()) {
            val first = heldSound.first()
            val free = all || !cutPending() || first.info.presentationTimeUs <= videoUs
            if (!free) return
            heldSound.removeFirst()
            routeAudio(first.data, first.info)
        }
    }

    private fun routeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val pts = info.presentationTimeUs
        val step = cuts.video(pts, info.size, key, next != null)
        if (step.closePrevious) closePrevious()
        val out = when (step.action) {
            FileCuts.Video.Drop -> null
            FileCuts.Video.Start -> open(checkNotNull(first), pts)?.also {
                current = it
                events.started(it.file, pts * NanosPerMicro)
            }
            FileCuts.Video.Cut -> cut(pts)
            FileCuts.Video.Write -> current
        } ?: return
        write(out, Track.Video, buffer, info)
        // From the frame to the encoder's callback for it: not the queue or the write after (FBBD-278).
        out.encoded((enteredNanos - pts * NanosPerMicro) / NanosPerMicro)
        if (step.askKeyframe) events.nextWanted()
        if (out.bytes >= target.maxFileBytes) events.full()
    }

    /** Starts [next] at the keyframe at [pts]; the file before stays open for its last sound. */
    private fun cut(pts: Long): Out? {
        val done = current
        val file = next
        if (done == null || file == null) return current
        next = null
        // Unopened, the recording fails and the full file stays current, for stop() to finish as the last.
        return open(file, pts)?.also { opened ->
            previous = done
            current = opened
            if (!audioOn()) closePrevious()
            events.handedOver(done.file, done.mediaMicros, file, pts * NanosPerMicro)
        }
    }

    private fun routeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val step = cuts.audio(info.presentationTimeUs, info.size)
        if (step.closePrevious) closePrevious()
        when (step.action) {
            FileCuts.Audio.Drop -> Unit
            FileCuts.Audio.Previous -> previous?.let { write(it, Track.Audio, buffer, info) }
            FileCuts.Audio.Current -> current?.let { write(it, Track.Audio, buffer, info) }
        }
    }

    private fun audioOn() = audioWanted && audioFormat != null

    /** A new MP4 starting at [baseUs]; null, with the recording failed, when it cannot be opened. */
    @Suppress("TooGenericExceptionCaught") // MediaMuxer refuses a file or a format with I/O or runtime exceptions.
    private fun open(file: File, baseUs: Long): Out? {
        var muxer: MediaMuxer? = null
        return try {
            val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(target.orientation)
            val v = m.addTrack(checkNotNull(videoFormat))
            val a = if (audioOn()) m.addTrack(checkNotNull(audioFormat)) else -1
            m.start()
            Out(file, m, v, a, baseUs)
        } catch (e: Exception) {
            DebugLog.w(Tag, "${file.name} not opened", e)
            runCatching { muxer?.release() }
            file.delete()
            events.failed("file not opened: ${e.javaClass.simpleName}")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // MediaMuxer refuses a sample with a runtime exception.
    private fun write(out: Out, track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val index = if (track == Track.Video) out.video else out.audio
        val pts = info.presentationTimeUs - out.baseUs
        if (index < 0 || pts < 0 || out.failed) return
        val shifted = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
        try {
            val begin = System.nanoTime()
            out.muxer.writeSampleData(index, buffer, shifted)
            if (track == Track.Video) writeNanos += System.nanoTime() - begin
        } catch (e: RuntimeException) {
            out.failed = true
            DebugLog.w(Tag, "${out.file.name}: sample not written", e)
            events.failed("muxer failed: ${e.javaClass.simpleName}")
            return
        }
        out.bytes += info.size
        if (track == Track.Video) {
            out.frames++
            out.lastUs = info.presentationTimeUs
        } else {
            out.sounds++
        }
    }

    /** Closes the file before the current one, off the encoder's threads. */
    private fun closePrevious() {
        val p = previous ?: return
        previous = null
        cuts.previousClosed()
        closer.execute { events.closed(p.file, p.stats(), finish(p)) }
    }

    /** Finishes [out]'s MP4; returns an error when it could not be. */
    @Suppress("TooGenericExceptionCaught") // MediaMuxer reports a file it cannot finish as a runtime exception.
    private fun finish(out: Out): String? {
        val error = try {
            out.muxer.stop()
            null
        } catch (e: RuntimeException) {
            DebugLog.w(Tag, "${out.file.name}: not finished", e)
            "no valid data"
        }
        runCatching { out.muxer.release() }
        return error
    }

    private inner class VideoCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            drained(codec, index, info, Track.Video, videoDone)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            DebugLog.w(Tag, "video encoder error", e)
            if (!ending) events.failed("video encoder: ${e.diagnosticInfo}")
            videoDone.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) { if (videoFormat == null) videoFormat = format }
        }
    }

    private inner class AudioCallback(private val source: PcmSource) : MediaCodec.Callback() {
        private var lastUs = 0L

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return
            buffer.limit(minOf(buffer.capacity(), PcmChunkBytes))
            val read = if (ending) null else source.read(buffer)
            runCatching {
                if (read == null) {
                    codec.queueInputBuffer(index, 0, 0, lastUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    lastUs = read.nanos / NanosPerMicro
                    codec.queueInputBuffer(index, 0, read.bytes, lastUs, 0)
                }
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            drained(codec, index, info, Track.Audio, audioDone)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            DebugLog.w(Tag, "AAC encoder error; the rest is silent", e)
            synchronized(lock) { giveUpSound() }
            audioDone.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) { if (audioFormat == null && audioWanted) audioFormat = format }
        }
    }

    /**
     * One encoded sample out of [codec]: copied and handed back at once, then into a file on the writer
     * thread, unless it is codec setup; [done] once the writer has everything before the end.
     */
    private fun drained(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        track: Track,
        done: CountDownLatch,
    ) {
        val begin = clockNanos()
        val setupOnly = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val sample = !setupOnly && info.size > 0
        if (sample && track == Track.Video) timing.encoded(begin - info.presentationTimeUs * NanosPerMicro)
        if (!mux) {
            runCatching { codec.releaseOutputBuffer(index, false) }
            if (sample && track == Track.Video) timing.released(clockNanos() - begin)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
            return
        }
        // A stalled writer gets nothing more: the buffer goes back at once, as without MP4, while the file ends.
        val copy = if (sample && !backlog.stalled) {
            runCatching { codec.getOutputBuffer(index) }.getOrNull()?.let { pooledCopy(track, it, info) }
        } else {
            null
        }
        runCatching { codec.releaseOutputBuffer(index, false) }
        if (copy != null) queue(track, copy, info.copy(), begin, clockNanos() - begin)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) onWriter { done.countDown() }
    }

    /**
     * [data] to the writer thread; waits only while the writer's [backlog] is full. A writer still
     * behind after [backlogWaitMs] fails the recording: the copy is dropped, nothing more is queued, and
     * the file is finished with what the writer has (audit A15). Until 1.7.1 the copy was queued
     * regardless, past the budget.
     */
    private fun queue(track: Track, data: ByteBuffer, info: MediaCodec.BufferInfo, entered: Long, heldNanos: Long) {
        val capacity = data.capacity()
        if (!backlog.reserve(capacity, if (track == Track.Video) videoFull else null)) {
            if (backlog.claimStallReport()) {
                DebugLog.w(Tag, "writer stalled: ${backlog.takenKib} KiB waiting after $backlogWaitMs ms")
                events.failed("writer stalled")
            }
            return
        }
        if (track == Track.Video) timing.held(heldNanos, info.size)
        val queued = System.nanoTime()
        onWriter {
            backlog.written(capacity, ::writeFailed) {
                // After a failed write nothing more goes in: the file may be half-handled.
                if (writeFailure.get()) return@written
                beforeWrite?.invoke()
                val begin = System.nanoTime()
                synchronized(lock) {
                    val locked = System.nanoTime()
                    writeNanos = 0L
                    if (track == Track.Video) enteredNanos = entered
                    sample(track, data, info)
                    if (track == Track.Video) timing.written(info.size, begin - queued, locked - begin, writeNanos)
                }
            }
            val spare = if (track == Track.Video) pool else soundPool
            if (spare.size < PoolSize) spare.offer(data)
        }
    }

    /** A write on the writer thread threw (audit 30 Oct BD-04): reported once, and the recording cannot go on. */
    private fun writeFailed(e: Exception) {
        if (!writeFailure.compareAndSet(false, true)) return
        DebugLog.e(Tag, "writer failed", e)
        events.failed("writer: ${e.javaClass.simpleName}")
    }

    /** Runs [task] on the writer thread, after every sample before it; dropped once the writer is shut down. */
    private fun onWriter(task: () -> Unit) {
        try {
            writer.execute(task)
        } catch (_: RejectedExecutionException) {
            DebugLog.w(Tag, "writer already stopped: a sample after the end is dropped")
        }
    }

    /** A copy of [info]'s bytes of [buffer], in a buffer from [track]'s pool when one is big enough. */
    private fun pooledCopy(track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
        val video = track == Track.Video
        val target = (if (video) pool else soundPool).poll()?.takeIf { it.capacity() >= info.size }
            ?: ByteBuffer.allocateDirect(maxOf(info.size, if (video) MinPooledBytes else MinPooledSoundBytes))
        val view = buffer.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        target.clear()
        return target.put(view).apply { flip() }
    }

    private enum class Track { Video, Audio }

    private class Waiting(val track: Track, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    /** One open MP4: its tracks, where its time starts ([baseUs]) and what it holds so far. */
    private class Out(val file: File, val muxer: MediaMuxer, val video: Int, val audio: Int, val baseUs: Long) {
        var frames = 0L
        var sounds = 0L
        var bytes = 0L
        var lastUs = baseUs
        var failed = false
        private val latency = IntArray(LatencyBuckets)

        val mediaMicros: Long get() = lastUs - baseUs

        /** A frame encoded [micros] after it was taken. */
        fun encoded(micros: Long) {
            latency[(micros / MicrosPerMilli).toInt().coerceIn(0, LatencyBuckets - 1)]++
        }

        fun stats() = FileStats(frames, mediaMicros, sounds > 0, encodeMs())

        private fun encodeMs(): String {
            val n = latency.sum()
            if (n == 0) return "-"
            fun quantile(q: Double): Int {
                var seen = 0
                latency.forEachIndexed { ms, count ->
                    seen += count
                    if (seen >= q * n) return ms
                }
                return LatencyBuckets - 1
            }
            return "${quantile(Half)}/${quantile(P95)}/${latency.indexOfLast { it > 0 }}"
        }
    }

    private companion object {
        const val Tag = "WideRecorder"
        const val NanosPerMicro = 1_000L
        const val MicrosPerMilli = 1_000L
        const val LatencyBuckets = 1_000
        const val Half = 0.5
        const val P95 = 0.95

        /** Room left in a file once it asks for the keyframe that starts the next: ~20 s at 2.7K·60. */
        const val CutMarginBytes = 200_000_000L
        /** A codec at full load ends its stream within a few frames; ten seconds is far beyond that. */
        const val DrainSeconds = 10L

        /** Finishing a 3.8 GB MP4 writes its whole index; allow it far longer than draining the codecs. */
        const val CloseSeconds = 60L
        const val AudioWaitUs = 1_000_000L

        /** About 4 s of AAC frames (1024 samples at 48 kHz each). */
        const val MaxHeldSound = 200

        /** ~21 ms of 48 kHz mono per read, so each sound sample is timed closely. */
        const val PcmChunkBytes = 2_048

        /**
         * Copies waiting for the writer, by capacity: 64 MiB, at least 4 s of video at 60 fps (a video copy
         * holds 256 KiB or more). The camera test's writer was at most 2 behind (FBBD-278).
         */
        const val MaxBacklogKib = 65_536

        /** How long the encoder waits for room before it takes the writer as stalled and ends the file. */
        const val BacklogWaitMs = 2_000L

        /** Copies kept for reuse, and their least size: a 1080p · 60 frame is mostly well under it. */
        const val PoolSize = 64
        const val MinPooledBytes = 256 * 1_024

        /** An AAC frame at 192 kbit/s is about 0.5 KB. */
        const val MinPooledSoundBytes = 4 * 1_024
    }
}

/** A copy of [info]'s bytes of this buffer, for a sample held until the files can open. */
private fun ByteBuffer.copy(info: MediaCodec.BufferInfo): ByteBuffer {
    val view = duplicate().apply {
        position(info.offset)
        limit(info.offset + info.size)
    }
    return ByteBuffer.allocateDirect(info.size).put(view).apply { flip() }
}

private fun MediaCodec.BufferInfo.copy() =
    MediaCodec.BufferInfo().also { it.set(0, size, presentationTimeUs, flags) }

/** Operating rates tried in turn: twice the frame rate (room for bursts), the frame rate, none. */
private fun operatingRates(fps: Int): List<Int?> = listOf(fps * 2, fps, null)

/** A started video encoder, the surface it takes frames from, and how it was set up (for the log). */
internal class StartedEncoder(val codec: MediaCodec, val surface: Surface, val setup: String)

/**
 * A started video encoder for [target] in asynchronous mode on [handler], fed from a surface: H.264,
 * unless the phone lists performance points and only its HEVC encoder covers the size and rate. The
 * first operating rate it accepts at real-time priority wins (FBBD-246).
 */
@Suppress("TooGenericExceptionCaught") // A refused setup is retried with less; the last refusal is thrown.
internal fun videoEncoder(target: WideTarget, callback: MediaCodec.Callback, handler: Handler): StartedEncoder {
    val avc = covers(MediaFormat.MIMETYPE_VIDEO_AVC, target.width, target.height, target.fps)
    val hevc = covers(MediaFormat.MIMETYPE_VIDEO_HEVC, target.width, target.height, target.fps)
    val mime = pickMime(avc, hevc)
    val points = "points avc=${avc ?: "-"} hevc=${hevc ?: "-"}"
    var refusal: Exception? = null
    for (rate in operatingRates(target.fps)) {
        val codec = MediaCodec.createEncoderByType(mime)
        var surface: Surface? = null
        try {
            codec.setCallback(callback, handler)
            codec.configure(videoFormat(mime, target, rate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            return StartedEncoder(codec, surface, "${codec.name} $mime operating_rate=${rate ?: "-"} $points")
        } catch (e: Exception) {
            DebugLog.w("WideRecorder", "${codec.name} refused operating rate $rate", e)
            surface?.release()
            codec.release()
            refusal = e
        }
    }
    throw checkNotNull(refusal)
}

private fun videoFormat(mime: String, target: WideTarget, operatingRate: Int?): MediaFormat =
    MediaFormat.createVideoFormat(mime, target.width, target.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, target.bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, target.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (operatingRate != null) {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
        }
    }

private const val AacBitrate = 192_000

private fun audioEncoder(pcm: PcmSource, callback: MediaCodec.Callback, handler: Handler): MediaCodec {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, AacBitrate)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    try {
        codec.setCallback(callback, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (e: IllegalStateException) {
        codec.release()
        throw e
    } catch (e: IllegalArgumentException) {
        codec.release()
        throw e
    }
    return codec
}

/**
 * The video type: H.264, which every player and CircuitTools take, unless its encoder is known not to
 * keep up ([avc] false: its performance points do not cover the size and rate) and HEVC's is ([hevc]).
 */
internal fun pickMime(avc: Boolean?, hevc: Boolean?): String =
    if (avc == false && hevc == true) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

/** Whether the default [mime] encoder's performance points cover [w]×[h] at [fps]; null when it lists none. */
internal fun covers(mime: String, w: Int, h: Int, fps: Int): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { it.isEncoder && mime in it.supportedTypes }
    val points = info?.let {
        runCatching { it.getCapabilitiesForType(mime).videoCapabilities?.supportedPerformancePoints }.getOrNull()
    }
    val want = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, fps)
    return when {
        info == null -> false
        points.isNullOrEmpty() -> null
        else -> points.any { it.covers(want) }
    }
}
