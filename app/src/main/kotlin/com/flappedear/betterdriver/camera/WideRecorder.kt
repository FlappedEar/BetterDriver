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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the phone's back camera at a frame size CameraX has no preset for, such as 2688×1512 for
 * 2.7K (FBBD-239): Camera2 draws into [WideEncoder]'s video encoder through a [FrameRelay], and the
 * encoder writes the MP4s itself. CameraX must have nothing bound while it runs. Until 0.4.0 a
 * MediaRecorder took the frames; it kept only ~50 of 60 fps (FBBD-246) and failed handing over to the
 * next file at 3.8 GB (FBBD-248).
 *
 * A file that nears [WideTarget.maxFileBytes] hands over to the next one ([Listener.nextFile]) at a
 * keyframe without closing the camera, as the CameraX path does, so video has no gap between files.
 * Every end, error or not, closes the camera and is reported once through [Listener.ended]. Callbacks
 * arrive on the main thread. [realtime]: the camera's sensor timestamps are boot time, not monotonic;
 * the frames it draws into the encoder are monotonic either way, as are the encoder's times.
 */
@Suppress("TooManyFunctions") // One function per camera and encoder step.
internal class WideRecorder(
    private val context: Context,
    private val cameraId: String,
    private val target: WideTarget,
    private val audio: Boolean,
    private val realtime: Boolean,
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

        /** [file], already handed over, could not be finished ([error]) and has been deleted. */
        fun lost(file: File, error: String)

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

    private var encoder: WideEncoder? = null
    private var relay: FrameRelay? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var file: File? = null
    private var ended = false

    /** Whether [Listener.started] was called: a session made again after the relay ([checkRelay]) is no new file. */
    private var announced = false
    private var openRetries = 0

    /** The sensor timestamp of the newest frame, and where the current file starts; 0 before any. */
    private val lastNanos = AtomicLong(0)
    private val firstNanos = AtomicLong(0)

    /** Sensor timestamp less encoder time: boot time less monotonic when [realtime], else 0. */
    private var sensorShift = 0L

    /**
     * Since the camera started: frames it delivered, failed or lost on the way to the encoder, and
     * sensor frames no request was waiting for (the encoder held the camera back), so a log tells
     * where frames went (FBBD-246). Each file's share is the difference from its start ([CameraCount]).
     */
    private val cameraFrames = AtomicLong(0)
    private val failedFrames = AtomicLong(0)
    private val lostBuffers = AtomicLong(0)
    private val skippedFrames = AtomicLong(0)
    private val fileStarts = ConcurrentHashMap<String, CameraCount>()
    private val fileEnds = ConcurrentHashMap<String, CameraCount>()

    /** The camera's latest sensor timestamps, for the [FrameRelay] to tell which clock its frames carry. */
    private val recentSensor = ArrayDeque<Long>()

    private fun sensorSeen(t: Long) = synchronized(recentSensor) { t in recentSensor }

    /** Sensor timestamp of the newest frame; 0 before the first. */
    val lastFrameNanos: Long get() = lastNanos.get()

    /** Recorded media time of the current file, from its first frame to its newest; null before a frame. */
    val mediaMicros: Long? get() = mediaOf(firstNanos.get(), lastNanos.get())

    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val t = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(recentSensor) {
                recentSensor.addLast(t)
                if (recentSensor.size > RecentSensorTimes) recentSensor.removeFirst()
            }
            val previous = lastNanos.getAndSet(t)
            val frame = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            if (previous > 0) skippedFrames.addAndGet(framesSkipped(t - previous, frame))
            cameraFrames.incrementAndGet()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            failedFrames.incrementAndGet()
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
            lostBuffers.incrementAndGet()
        }
    }

    private fun count() = CameraCount(cameraFrames.get(), failedFrames.get(), lostBuffers.get(), skippedFrames.get())

    /** What [WideEncoder] reports, moved to the main thread where the recording's state lives. */
    private val encoded = object : WideEncoder.Events {
        override fun nextWanted() {
            main.post {
                if (!ended) encoder?.offerNext(listener.nextFile())
            }
        }

        override fun started(file: File, baseNanos: Long) {
            firstNanos.set(baseNanos + sensorShift)
            fileStarts[file.name] = count()
        }

        override fun handedOver(done: File, mediaMicros: Long, next: File, baseNanos: Long) {
            val now = count()
            fileEnds[done.name] = now
            fileStarts[next.name] = now
            // Delivered even when a stop has begun: the encoder already moved on, and its end reports next.
            // The new start changes on the main thread with the file, so a progress report never pairs them wrongly.
            main.post {
                firstNanos.set(baseNanos + sensorShift)
                file = next
                listener.handedOver(done, mediaMicros, next)
            }
        }

        override fun closed(file: File, stats: WideEncoder.FileStats, error: String?) {
            logFile(file, stats, error)
            // An MP4 that was not finished cannot be played: it leaves the session, as the last one would.
            if (error != null) {
                file.delete()
                main.post { listener.lost(file, error) }
            }
        }

        override fun full() {
            main.post { finish(error = null, full = true) }
        }

        override fun failed(error: String) {
            main.post { finish(error = error, full = false) }
        }
    }

    /**
     * Starts the encoder for [first] and opens the camera; frames come once the camera is
     * configured. Throws when the encoder cannot be set up for [target], so the caller falls back to
     * another mode at once. Later failures arrive as [Listener.ended].
     */
    @SuppressLint("MissingPermission") // CameraService records only with the camera permission granted.
    @Suppress("TooGenericExceptionCaught") // Cleans up, then hands the refusal to the caller.
    fun start(first: File) {
        file = first
        try {
            sensorShift = if (realtime) SystemClock.elapsedRealtimeNanos() - System.nanoTime() else 0L
            val e = WideEncoder(target, if (audio) MicSource() else null, System::nanoTime, encoded)
            encoder = e
            DebugLog.i(Tag, "encoder ${e.setup}")
            e.start(first)
            open()
        } catch (e: Exception) {
            ended = true
            encoder?.let { enc -> runCatching { enc.stop() } }
            encoder = null
            first.delete()
            thread.quitSafely()
            throw e
        }
    }

    @SuppressLint("MissingPermission") // CameraService records only with the camera permission granted.
    private fun open() {
        context.getSystemService(CameraManager::class.java).openCamera(cameraId, opened, main)
    }

    /** Stops recording; [Listener.ended] follows. */
    fun stop() {
        finish(error = null, full = false)
    }

    private val opened = object : CameraDevice.StateCallback() {
        @Suppress("TooGenericExceptionCaught") // Camera2 reports these as runtime and access exceptions.
        override fun onOpened(camera: CameraDevice) {
            val e = encoder
            if (ended || e == null) {
                camera.close()
                return
            }
            device = camera
            try {
                createSession(camera, cameraSurface(e))
            } catch (x: Exception) {
                // A camera lost or a surface refused here throws instead of calling onConfigureFailed.
                DebugLog.w(Tag, "capture session not created", x)
                finish(error = "session failed: ${x.javaClass.simpleName}", full = false)
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
                            open()
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

    /**
     * Where the camera draws: a [FrameRelay] in front of the encoder (FBBD-246), or the encoder's own
     * surface before Android 10 or when the relay cannot be set up.
     */
    @Suppress("TooGenericExceptionCaught") // ImageReader and ImageWriter refuse a size or format at runtime.
    private fun cameraSurface(e: WideEncoder): Surface {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return e.surface
        return try {
            FrameRelay(e.surface, target.width, target.height, sensorShift, ::sensorSeen).also {
                relay = it
                DebugLog.i(Tag, "camera frames through a relay")
            }.surface
        } catch (x: Exception) {
            DebugLog.w(Tag, "no relay: the camera draws straight into the encoder", x)
            e.surface
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

    @Suppress("TooGenericExceptionCaught") // MediaCodec and Camera2 report refusals as runtime exceptions.
    private fun begin(camera: CameraDevice, s: CameraCaptureSession, surface: Surface) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, advertisedRange())
            }.build()
            s.setRepeatingRequest(request, frames, background)
            if (!announced) {
                announced = true
                file?.let(listener::started)
            }
            if (relay != null) main.postDelayed(::checkRelay, RelayCheckMs)
        } catch (e: Exception) {
            DebugLog.w(Tag, "could not start ${target.label}", e)
            finish(error = "start failed: ${e.javaClass.simpleName}", full = false)
        }
    }

    /**
     * The relay is new on the phone (FBBD-246): if the camera has given frames for [RelayCheckMs] but
     * none has reached a file, the camera is moved to draw straight into the encoder, as before the
     * relay, rather than lose the recording.
     */
    private fun checkRelay() {
        val r = relay
        val camera = device
        val e = encoder
        if (r == null || camera == null || e == null) return
        val stuck = !ended && firstNanos.get() == 0L && cameraFrames.get() > 0
        // Only from Android 10, where cameraSurface() made the relay.
        if (!stuck || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        DebugLog.w(Tag, "no frame through the relay in $RelayCheckMs ms (${r.counts()}): camera to the encoder")
        relay = null
        val s = session
        session = null
        background.post {
            runCatching { s?.close() }
            r.close()
            main.post { if (!ended) drawStraight(camera, e) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // As in onOpened: a refused surface ends the recording.
    private fun drawStraight(camera: CameraDevice, e: WideEncoder) {
        try {
            createSession(camera, e.surface)
        } catch (x: Exception) {
            DebugLog.w(Tag, "capture session not created", x)
            finish(error = "session failed: ${x.javaClass.simpleName}", full = false)
        }
    }

    /** An auto-exposure range the camera advertises for the target rate ([aeRange]); fixed if it has none. */
    private fun advertisedRange(): Range<Int> {
        val ranges = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val (lower, upper) = aeRange(ranges.map { it.lower to it.upper }, target.fps) ?: (target.fps to target.fps)
        return Range(lower, upper)
    }

    /**
     * Ends recording once: stops the camera, then drains the encoder and finishes the last MP4 on the
     * background thread (finalizing a large MP4 takes a moment), then reports on the main thread.
     */
    private fun finish(error: String?, full: Boolean) {
        if (ended) return
        ended = true
        val f = file
        val e = encoder
        val s = session
        val d = device
        val r = relay
        encoder = null
        session = null
        device = null
        relay = null
        background.post {
            runCatching { s?.stopRepeating() }
            runCatching { s?.close() }
            runCatching { d?.close() }
            // The relay goes before the encoder's surface does; what it handed on is still encoded.
            // A relay exists only from Android 10, where cameraSurface() made it.
            if (r != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                r.close()
                DebugLog.i(Tag, "camera frames ${r.counts()}")
            }
            // Frames still on their way are encoded before the end of the stream.
            val closed = e?.stop()
            val last = closed?.file ?: f
            val hadData = closed != null && closed.stats.frames > 0 && closed.error == null
            if (closed != null) logFile(closed.file, closed.stats, closed.error)
            if (last != null && !hadData) last.delete()
            val media = closed?.stats?.mediaMicros ?: 0
            main.post {
                thread.quitSafely()
                if (last != null) listener.ended(last, media, full, error ?: closed?.error, hadData)
            }
        }
    }

    /** One line per finished file: frames and rate, size, where camera frames went, encoding time, sound. */
    private fun logFile(f: File, stats: WideEncoder.FileStats, error: String?) {
        val start = fileStarts.remove(f.name) ?: CameraCount()
        val c = (fileEnds.remove(f.name) ?: count()) - start
        val sound = if (audio) " audio=${if (stats.audio) "yes" else "no"}" else ""
        DebugLog.i(
            Tag,
            "${f.name}: ${target.label} ${fpsLine(stats.frames, stats.mediaMicros)} ${f.length()} bytes " +
                "camera=${c.frames} camera_failed=${c.failed} buffers_lost=${c.lost} skipped=${c.skipped} " +
                "encode_ms=${stats.encodeMs}$sound" + error?.let { " error=$it" }.orEmpty(),
        )
    }

    private companion object {
        const val Tag = "WideRecorder"
        const val MaxOpenRetries = 3
        const val OpenRetryMs = 500L

        /** A keyframe reaches the encoder's output within a few frames; 2 s is far beyond that. */
        const val RelayCheckMs = 2_000L

        /** About half a second of frames at 60 fps: longer than a frame takes to reach the relay. */
        const val RecentSensorTimes = 32
    }
}

/** Camera frames since the camera started: delivered, failed, lost, and skipped by the sensor. */
private data class CameraCount(val frames: Long = 0, val failed: Long = 0, val lost: Long = 0, val skipped: Long = 0) {
    operator fun minus(o: CameraCount) =
        CameraCount(frames - o.frames, failed - o.failed, lost - o.lost, skipped - o.skipped)
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
