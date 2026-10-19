package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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

    /** Sensor timestamp of the newest frame; 0 before the first. */
    val lastFrameNanos: Long get() = lastNanos.get()

    /** Recorded media time of the current file, from its first frame to its newest; null before a frame. */
    val mediaMicros: Long? get() = mediaOf(firstNanos.get(), lastNanos.get())

    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val t = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            firstNanos.compareAndSet(0, t)
            lastNanos.set(t)
            fileFrames.incrementAndGet()
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
        override fun onOpened(camera: CameraDevice) {
            if (ended) {
                camera.close()
                return
            }
            device = camera
            createSession(camera, r.surface)
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
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(target.fps, target.fps))
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
            if (f != null) logFile(f, media ?: 0)
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

    private fun logFile(f: File, media: Long) {
        DebugLog.i(Tag, "${f.name}: ${target.label} ${fpsLine(fileFrames.get(), media)} ${f.length()} bytes")
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
