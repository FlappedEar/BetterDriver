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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Encodes the 2.7K camera stream itself, in place of MediaRecorder (FBBD-246, FBBD-248): Camera2
 * draws into [surface], a MediaCodec video encoder run at real-time priority with room above the
 * frame rate, sound from [pcm] goes through an AAC encoder, and [FileCuts] decides which MP4 each
 * sample goes to, so a full file hands over to the next at a keyframe with no frame or sound lost.
 *
 * [events] are called on the encoder's own threads. [stop] blocks; call it off the main thread.
 * A file asks for the keyframe that starts the next one [cutMarginBytes] before its size limit.
 */
@Suppress("TooManyFunctions") // One function per encoder, muxer and file step.
internal class WideEncoder(
    private val target: WideTarget,
    private val pcm: PcmSource?,
    private val clockNanos: () -> Long,
    private val events: Events,
    cutMarginBytes: Long = CutMarginBytes,
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

    /** What went into one file. [encodeMs]: time from frame to encoded sample, p50/p95/max. */
    data class FileStats(val frames: Long, val mediaMicros: Long, val audio: Boolean, val encodeMs: String)

    private val videoThread = HandlerThread("WideVideo").apply { start() }
    private val audioThread = HandlerThread("WideAudio").apply { start() }
    private val closer = Executors.newSingleThreadExecutor { Thread(it, "WideMuxerClose") }
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
            // A next file that could not be opened left the full one current (cut), so it is the last.
            releaseSound(all = true)
            val c = current
            closePrevious()
            current = null
            val n = next
            next = null
            c to n
        }
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
        out.encoded(clockNanos() / NanosPerMicro - pts)
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
            out.muxer.writeSampleData(index, buffer, shifted)
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
            audioDone.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) { if (audioFormat == null && audioWanted) audioFormat = format }
        }
    }

    /** One encoded sample out of [codec]: into a file, unless it is codec setup; [done] at the end. */
    private fun drained(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        track: Track,
        done: CountDownLatch,
    ) {
        val setupOnly = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!setupOnly && info.size > 0) {
            runCatching { codec.getOutputBuffer(index) }.getOrNull()?.let { sample(track, it, info) }
        }
        runCatching { codec.releaseOutputBuffer(index, false) }
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
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
