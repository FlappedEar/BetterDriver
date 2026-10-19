package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the phone's back camera at a frame size CameraX has no preset for, such as 2688×1512 for
 * 2.7K (FBBD-239): Camera2 feeds a [MediaRecorder] surface directly. CameraX must have nothing bound
 * while it runs.
 *
 * A file that nears [maxFileBytes] hands over to the next one ([Listener.nextFile]) without closing
 * the camera, as the CameraX path does, so video has no gap between files. Every end, error or not,
 * closes the camera and is reported once through [Listener.ended]. Callbacks arrive on the main thread.
 */
@Suppress("TooManyFunctions") // One function per camera and recorder step.
internal class WideRecorder(
    private val context: Context,
    private val cameraId: String,
    private val target: WideTarget,
    private val audio: Boolean,
    private val listener: Listener,
) {
    /** How the recording reports its files. All calls arrive on the main thread. */
    interface Listener {
        /** The file after the current one, asked for when the current one is nearly full. */
        fun nextFile(): File

        /** [file] is recording; its first frame may still be on its way. */
        fun started(file: File)

        /** [file] is full and finished, and [next] has taken over without closing the camera. */
        fun handedOver(file: File, mediaMicros: Long, next: File)

        /**
         * Recording ended and the camera is closed. [error] is null for a stop that was asked for or a
         * file that filled up with no next one ready ([full]). [hadData] is false when no frame was
         * recorded, in which case the file has been deleted.
         */
        fun ended(file: File, mediaMicros: Long, full: Boolean, error: String?, hadData: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("WideRecorder").apply { start() }
    private val background = Handler(thread.looper)

    private var recorder: MediaRecorder? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var file: File? = null
    private var pending: File? = null
    private var ended = false
    private var openRetries = 0

    /** The sensor timestamp of the newest frame, and of the first frame of the current file; 0 before any. */
    private val lastNanos = AtomicLong(0)
    private val firstNanos = AtomicLong(0)
    private val fileFrames = AtomicLong(0)

    /**
     * Per file: frames the camera failed or lost on the way to the recorder, and sensor frames no
     * request was waiting for (the recorder held the camera back), so a log tells where frames went (FBBD-246).
     */
    private val failedFrames = AtomicLong(0)
    private val lostBuffers = AtomicLong(0)
    private val skippedFrames = AtomicLong(0)

    /** Sensor timestamp of the newest frame; 0 before the first. */
    val lastFrameNanos: Long get() = lastNanos.get()

    /** Recorded media time of the current file, from its first frame to its newest; null before a frame. */
    val mediaMicros: Long? get() = mediaOf(firstNanos.get(), lastNanos.get())

    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val t = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val previous = lastNanos.getAndSet(t)
            val frame = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            if (!firstNanos.compareAndSet(0, t)) skippedFrames.addAndGet(framesSkipped(t - previous, frame))
            fileFrames.incrementAndGet()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            failedFrames.incrementAndGet()
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
            lostBuffers.incrementAndGet()
        }
    }

    /**
     * Prepares the recorder for [first] and opens the camera; recording starts once the camera is
     * configured. Throws when the recorder cannot be set up for [target], so the caller falls back to
     * another mode at once. Later failures arrive as [Listener.ended].
     */
    @SuppressLint("MissingPermission") // CameraService records only with the camera permission granted.
    @Suppress("TooGenericExceptionCaught") // Cleans up, then hands the refusal to the caller.
    fun start(first: File) {
        file = first
        try {
            val r = newRecorder(first)
            recorder = r
            open(r)
        } catch (e: Exception) {
            ended = true
            recorder?.release()
            recorder = null
            first.delete()
            thread.quitSafely()
            throw e
        }
    }

    @SuppressLint("MissingPermission") // CameraService records only with the camera permission granted.
    private fun open(r: MediaRecorder) {
        context.getSystemService(CameraManager::class.java).openCamera(cameraId, opened(r), main)
    }

    /** Stops recording; [Listener.ended] follows. */
    fun stop() {
        finish(error = null, full = false)
    }

    private fun opened(r: MediaRecorder) = object : CameraDevice.StateCallback() {
        @Suppress("TooGenericExceptionCaught") // Camera2 reports these as runtime and access exceptions.
        override fun onOpened(camera: CameraDevice) {
            if (ended) {
                camera.close()
                return
            }
            device = camera
            try {
                createSession(camera, r.surface)
            } catch (e: Exception) {
                // A camera lost or a surface refused here throws instead of calling onConfigureFailed.
                DebugLog.w(Tag, "capture session not created", e)
                finish(error = "session failed: ${e.javaClass.simpleName}", full = false)
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            if (ended) {
                camera.close()
                return
            }
            device = camera
            finish(error = "camera disconnected", full = false)
        }

        @Suppress("TooGenericExceptionCaught") // A failed reopen ends the recording like any camera error.
        override fun onError(camera: CameraDevice, code: Int) {
            camera.close()
            // CameraX closes the camera it had a moment after it is unbound: wait for it, briefly.
            val busy = code == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                code == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
            val canRetry = device == null && openRetries < MaxOpenRetries
            if (!ended && busy && canRetry) {
                openRetries++
                DebugLog.i(Tag, "camera busy ($code), retry $openRetries")
                main.postDelayed({
                    if (!ended) {
                        try {
                            open(r)
                        } catch (e: Exception) {
                            finish(error = "camera open failed: ${e.javaClass.simpleName}", full = false)
                        }
                    }
                }, OpenRetryMs)
                return
            }
            finish(error = "camera error $code", full = false)
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List) is the one call on every API level from 26.
    private fun createSession(camera: CameraDevice, surface: Surface) {
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (ended) {
                    s.close()
                    return
                }
                session = s
                begin(camera, s, surface)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                finish(error = "session not configured", full = false)
            }
        }, main)
    }

    @Suppress("TooGenericExceptionCaught") // MediaRecorder and Camera2 report refusals as runtime exceptions.
    private fun begin(camera: CameraDevice, s: CameraCaptureSession, surface: Surface) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, advertisedRange())
            }.build()
            resetFile()
            recorder?.start()
            s.setRepeatingRequest(request, frames, background)
            file?.let(listener::started)
        } catch (e: Exception) {
            DebugLog.w(Tag, "could not start ${target.label}", e)
            finish(error = "start failed: ${e.javaClass.simpleName}", full = false)
        }
    }

    /** An auto-exposure range the camera advertises for the target rate ([aeRange]); fixed if it has none. */
    private fun advertisedRange(): Range<Int> {
        val ranges = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val (lower, upper) = aeRange(ranges.map { it.lower to it.upper }, target.fps) ?: (target.fps to target.fps)
        return Range(lower, upper)
    }

    @SuppressLint("MissingPermission") // Audio is only asked for when RECORD_AUDIO is granted (CameraService).
    @Suppress("TooGenericExceptionCaught") // Releases the recorder, then rethrows.
    private fun newRecorder(first: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else legacyRecorder()
        try {
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(first)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(target.width, target.height)
            r.setVideoFrameRate(target.fps)
            clockFor(r, target.fps)
            r.setVideoEncodingBitRate(target.bitrate)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(AudioRate)
                r.setAudioEncodingBitRate(AudioBitrate)
            }
            r.setOrientationHint(target.orientation)
            r.setMaxFileSize(target.maxFileBytes)
            r.setOnInfoListener { _, what, _ -> main.post { onInfo(what) } }
            r.setOnErrorListener { _, what, extra -> main.post { finish("recorder error $what/$extra", false) } }
            r.prepare()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        return r
    }

    @Suppress("DEPRECATION") // MediaRecorder(Context) is API 31.
    private fun legacyRecorder() = MediaRecorder()

    /**
     * Tells the encoder it must keep up with [fps]. MediaRecorder sets the encoder's operating rate
     * only when a capture rate is set (AOSP StagefrightRecorder.setupVideoEncoder); without it the
     * encoder may run its clock for less, and the camera, held back, skips frames: Arek's 2026-10-19
     * 2.7K·60 replay kept 48.4 fps from a sensor at 59.5 (FBBD-246). A capture rate equal to the
     * frame rate is neither time lapse nor slow motion, so frame times and audio stay as they are.
     */
    @Suppress("TooGenericExceptionCaught") // A refusal only leaves the encoder's own clock.
    private fun clockFor(r: MediaRecorder, fps: Int) {
        try {
            r.setCaptureRate(fps.toDouble())
        } catch (e: Exception) {
            DebugLog.w(Tag, "capture rate $fps refused", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A refused next file only means this one fills up and ends.
    private fun onInfo(what: Int) {
        if (ended) return
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> if (pending == null) {
                val next = listener.nextFile()
                try {
                    recorder?.setNextOutputFile(next)
                    pending = next
                } catch (e: Exception) {
                    DebugLog.w(Tag, "next file refused: ${next.name}", e)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> handOver()
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> finish(error = null, full = true)
        }
    }

    private fun handOver() {
        val done = file
        val next = pending
        if (done == null || next == null) return
        val media = mediaMicros ?: 0
        logFile(done, media)
        file = next
        pending = null
        resetFile()
        listener.handedOver(done, media, next)
    }

    private fun resetFile() {
        firstNanos.set(0)
        fileFrames.set(0)
        failedFrames.set(0)
        lostBuffers.set(0)
        skippedFrames.set(0)
    }

    /**
     * Ends recording once: stops the camera and the recorder on the background thread (finalizing a
     * large MP4 takes a moment), then reports on the main thread.
     */
    private fun finish(error: String?, full: Boolean) {
        if (ended) return
        ended = true
        val f = file
        val r = recorder
        val s = session
        val d = device
        val next = pending
        val media = mediaMicros
        val hadData = media != null
        pending = null
        recorder = null
        session = null
        device = null
        background.post {
            val stopError = close(r, s, d, hadData).takeUnless { full }
            next?.delete()
            val err = error ?: stopError
            if (f != null && (!hadData || stopError != null)) f.delete()
            if (f != null) logFile(f, media ?: 0, if (audio && f.isFile) audioTrack(f) else null)
            main.post {
                thread.quitSafely()
                if (f != null) listener.ended(f, media ?: 0, full, err, hadData && stopError == null)
            }
        }
    }

    /** Closes everything; returns an error when the file could not be finished. */
    @Suppress("TooGenericExceptionCaught") // Each step may throw once the camera or recorder has failed.
    private fun close(r: MediaRecorder?, s: CameraCaptureSession?, d: CameraDevice?, hadData: Boolean): String? {
        runCatching { s?.stopRepeating() }
        var stopError: String? = null
        if (r != null) {
            try {
                if (hadData) r.stop()
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "recorder stop failed", e)
                stopError = "no valid data"
            }
            runCatching { r.reset() }
            r.release()
        }
        runCatching { s?.close() }
        runCatching { d?.close() }
        return stopError
    }

    /** [audio]: whether the finished file holds the sound asked for; null when not checked. */
    private fun logFile(f: File, media: Long, audio: Boolean? = null) {
        val missing = "camera_failed=${failedFrames.get()} buffers_lost=${lostBuffers.get()} " +
            "skipped=${skippedFrames.get()}" + audio?.let { " audio=${if (it) "yes" else "no"}" }.orEmpty()
        DebugLog.i(Tag, "${f.name}: ${target.label} ${fpsLine(fileFrames.get(), media)} ${f.length()} bytes $missing")
    }

    /**
     * Whether [f] has a sound track. The capture rate [clockFor] sets must leave sound on: AOSP records
     * it whenever the capture rate is not below the frame rate, while MediaRecorder's documentation
     * says a capture rate means time lapse without sound; the log says which held on the phone.
     */
    @Suppress("TooGenericExceptionCaught") // A file the retriever cannot read is reported as unknown.
    private fun audioTrack(f: File): Boolean? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(f.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) {
            DebugLog.w(Tag, "sound of ${f.name} not checked", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val Tag = "WideRecorder"
        const val AudioRate = 48_000
        const val AudioBitrate = 192_000
        const val MaxOpenRetries = 3
        const val OpenRetryMs = 500L
    }
}

/** What [WideRecorder] records: frame size and rate, bitrate, the MP4 rotation and the size of a file. */
internal data class WideTarget(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val orientation: Int,
    val maxFileBytes: Long,
) {
    val label: String get() = "${width}x$height@$fps"
}

private const val NanosPerMicro = 1_000L
private const val MicrosPerSecond = 1_000_000.0

/** Media time from the first frame [first] to the newest [last] (sensor nanoseconds); null before a frame. */
internal fun mediaOf(first: Long, last: Long): Long? =
    if (first <= 0 || last < first) null else (last - first) / NanosPerMicro

/** "3601 frames in 60.0 s = 60.0 fps", the rate a file was actually recorded at. */
internal fun fpsLine(frames: Long, mediaMicros: Long): String {
    val seconds = mediaMicros / MicrosPerSecond
    val fps = if (frames > 1 && seconds > 0) (frames - 1) / seconds else 0.0
    return "$frames frames in ${"%.1f".format(java.util.Locale.ROOT, seconds)} s = " +
        "${"%.1f".format(java.util.Locale.ROOT, fps)} fps"
}

/**
 * Sensor frames between two recorded ones [gapNanos] apart, at the sensor's frame time [frameNanos]:
 * 0 for back-to-back frames, 1 when one frame in between never reached the recorder (FBBD-246).
 */
internal fun framesSkipped(gapNanos: Long, frameNanos: Long): Long =
    // Rounded, as frame times jitter by a fraction of a frame.
    if (frameNanos <= 0 || gapNanos <= 0) 0 else (Math.round(gapNanos.toDouble() / frameNanos) - 1).coerceAtLeast(0L)
