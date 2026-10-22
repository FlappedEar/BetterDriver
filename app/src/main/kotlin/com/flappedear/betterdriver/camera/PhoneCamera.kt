package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoSink
import com.flappedear.betterdriver.recorder.video.VideoSource
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.video.VideoIndex
import java.io.File

private const val NanosPerMicro = 1_000L

/**
 * When the frame with sensor timestamp [frameNanos] was captured, on the session clock, given the
 * session time [nowMicros]; null before the first frame. The timestamp is on elapsedRealtime when
 * [realtime], else on System.nanoTime (Camera2's SENSOR_INFO_TIMESTAMP_SOURCE).
 */
private fun frameMicros(nowMicros: Long, frameNanos: Long, realtime: Boolean): Long? {
    if (frameNanos <= 0) return null
    val clock = if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
    return nowMicros - (clock - frameNanos) / NanosPerMicro
}
private const val CheckIntervalMs = 5_000L

/**
 * 1080p and 4K at this rate record through [WideRecorder] (FBBD-278): CameraX's own recorder kept 55-58
 * of 60 fps on Arek's Pixel 6 (4K: 53.7), the own path 59.5 with no skipped frame (camera test, 1.7.0).
 */
private const val OwnFps = 60

/** The CameraX presets the own path also records at [OwnFps]; CameraX takes them back on a refusal. */
private val OwnSizes = setOf(VideoSize.FHD, VideoSize.UHD)

/** How often the camera's frame counts go to the debug log (FBBD-278). */
private const val FrameLogMs = 60_000L

/** How often an own-encoder recording reports progress; [syncDue] keeps it to about once a second. */
private const val WideSyncMs = 250L
private const val RetryDelayMs = 3_000L
private const val MaxRestarts = 3
private const val ForecastSeconds = 10

/** Thermal readings go to the debug log at least this often, and at every status change (FBBD-146). */
private const val ThermalLogEvery = 6

/** Video stops below this much free space, well before the recorder warns (200 MB) or stops (50 MB). */
private const val MinFreeBytes = 1L shl 30

/**
 * A file ends and the next one starts at about this size (CameraX stops at 95 % of it): about 30 min at
 * 1080p, 10 min at 4K. A crash or a phone switching off loses only the open file, which has no index
 * yet, rather than the whole drive; and no file gets near the 4 GB limit of 32-bit MP4 offsets.
 */
internal const val MaxFileBytes = 4_000_000_000L

/**
 * The phone's back camera, recording with CameraX in the background while the track screen stays
 * on (FBBD-21). No preview: only a VideoCapture use case is bound.
 *
 * 2.7K has no CameraX preset, so that mode records through [WideRecorder] (Camera2 and MediaCodec)
 * with nothing bound to CameraX (FBBD-239); so does 1080p·60 since 1.6.4 and 4K·60 since 1.7.1
 * (FBBD-278, [OwnFps]).
 * Files, sync reports and stops work the same way.
 *
 * Each recording is one file in the session's video directory (phone_0001.mp4 and on). A restart
 * after an error ends the file and starts the next one, so a file always has one resolution and frame
 * rate. Video stops only at Android's thermal shutdown, which ends the file first (ThermalPolicy).
 * Every fault ends in [VideoSink.stopped] and [VideoSink.status];
 * nothing here throws, and nothing here can reach the recorder process.
 *
 * Main thread only.
 */
@Suppress("TooManyFunctions") // One function per step of a file's life, for two recording paths.
class PhoneCamera(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    index: VideoIndex,
    private val audio: Boolean,
    /** The chosen mode first, then the modes to fall back to when one cannot be bound ([ladderFrom]). */
    private val ladder: List<VideoMode>,
) : VideoSource {
    private val dir: File = index.videoDir
    private val clips = ClipIndexer(index)
    override val camera = CameraIds.PHONE

    private val main = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private val power = context.getSystemService(PowerManager::class.java)
    private lateinit var sink: VideoSink
    private var provider: ProcessCameraProvider? = null

    /** This camera's bound use case. Only it is unbound when this camera ends, never all of them. */
    private var bound: VideoCapture<Recorder>? = null

    /** The ladder step [bound] records at; -1 when nothing is bound. */
    private var boundStep = -1
    private var steps: List<VideoMode> = ladder
    private var policy = ThermalPolicy()

    private var recording: Recording? = null

    /** The own-encoder recording (2.7K, 1080p·60), when the current step uses it; [recording] is null then. */
    private var wide: WideRecorder? = null

    /** The own encoder failed 1080p or 4K this session: CameraX records them from then on. */
    private var ownRefused = false
    private var fileIndex = 0
    private var restarts = 0
    private var stopping = false

    /** Called once after a [stop] with nothing left to finish (audit A13). */
    private var whenStopped: (() -> Unit)? = null

    /** What the file being finalized ends with: why it stops, and the step to start next (if any). */
    private var endReason = "user"

    /**
     * Whether the file being recorded holds data (a Status event with recorded media time; CameraX's Start
     * event alone does not promise any). A file stopped before that ends with error 8 and no frame, so
     * thermal checks wait for it.
     */
    private var fileStarted = false

    /** Android reported thermal shutdown this session: no file is started again. */
    private var shutdown = false

    private val checker = object : Runnable {
        override fun run() {
            if (stopping) return
            check()
            main.postDelayed(this, CheckIntervalMs)
        }
    }

    private var thermalLog = ThermalLog(ladder)

    /**
     * The sensor timestamp of the newest frame the camera captured, and whether that timestamp is on
     * elapsedRealtime (else on System.nanoTime). With each sync report it says when that frame was
     * taken on the session clock, so the video's sync and drift are measured by the camera's own
     * frames, not by when the report arrived (FBBD-21).
     */
    @Volatile
    private var lastFrameNanos = 0L
    private var realtimeFrames = false

    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val sensor = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            lastFrameNanos = sensor
            cameraFrames?.completed(
                sensor,
                result.get(CaptureResult.SENSOR_FRAME_DURATION),
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { it.lower to it.upper },
                result.frameNumber,
            )
            cameraFrames?.processing(
                result.get(CaptureResult.NOISE_REDUCTION_MODE),
                result.get(CaptureResult.EDGE_MODE),
                result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                result.get(CaptureResult.TONEMAP_MODE),
                result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
                result.get(CaptureResult.SHADING_MODE),
                result.get(CaptureResult.HOT_PIXEL_MODE),
            )?.let { DebugLog.i(Tag, it) }
            resultChanges?.takeIf { it.due(sensor) }?.let { changes ->
                val values = result.keys.associate { key ->
                    key.name to ResultChanges.text(runCatching { result.get(key) }.getOrNull())
                }
                changes.snapshot(values).forEach { DebugLog.i(Tag, it) }
            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            cameraFrames?.failed()
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            cameraFrames?.bufferLost()
        }
    }

    /** What the bound camera delivers, logged once a minute (FBBD-278); null while nothing is bound. */
    @Volatile
    private var cameraFrames: CameraFrames? = null

    /** The camera's own result values that switch partway through a file (FBBD-278); null while nothing is bound. */
    @Volatile
    private var resultChanges: ResultChanges? = null

    /** This process's busiest threads, logged with each frames line (FBBD-278). */
    private val threadCpu = ThreadCpu()

    private val frameLog = object : Runnable {
        override fun run() {
            logFrames()
            if (!stopping) main.postDelayed(this, FrameLogMs)
        }
    }

    /** The thermal status last written to the session; only changes are written (FBBD-183). */
    private var sessionThermal: Int? = null

    /** Set on Android 10+, where thermal status exists. */
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    @Suppress("TooGenericExceptionCaught") // Any camera fault ends video, never the recording.
    override fun start(sink: VideoSink) {
        this.sink = sink
        dir.mkdirs()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                // Stopped before the camera was ready: no thermal listener or check is left behind.
                if (stopping) return@addListener
                steps = supportedSteps(p)
                if (steps.isEmpty()) {
                    sink.status("Not supported", ok = false)
                    return@addListener
                }
                policy = ThermalPolicy()
                shutdown = false
                thermalLog = ThermalLog(steps)
                sessionThermal = null
                DebugLog.i(Tag, "steps ${steps.joinToString { it.label }} (chosen ${ladder.first().label})")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val listener = PowerManager.OnThermalStatusChangedListener { check() }
                    thermalListener = listener
                    power.addThermalStatusListener(executor, listener)
                }
                main.postDelayed(checker, CheckIntervalMs)
                main.postDelayed(frameLog, FrameLogMs)
                // A phone already at thermal shutdown does not start video at all.
                if (thermalAction() == ThermalAction.StopVideo) {
                    shutdown = true
                    endReason = "thermal"
                    sink.status(stoppedText(endReason), ok = false)
                } else {
                    begin(0)
                }
            } catch (e: Exception) {
                sink.status("Camera error", ok = false)
                DebugLog.w(Tag, "camera provider failed", e)
            }
        }, executor)
    }

    override fun stop() {
        if (stopping) return
        stopping = true
        main.removeCallbacks(checker)
        main.removeCallbacks(frameLog)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let(power::removeThermalStatusListener)
        endReason = "user"
        val r = recording
        val w = wide
        when {
            r != null -> r.stop()
            w != null -> w.stop()
            else -> unbindOwn(provider, bound)
        }
    }

    /**
     * Stops, then calls [finished] once the last file is finished and nothing records any more: at once
     * when nothing was recording. The camera service holds its foreground until then (audit A13).
     */
    fun stop(finished: () -> Unit) {
        whenStopped = finished
        stop()
        stoppedIfIdle()
    }

    private fun stoppedIfIdle() {
        if (!stopping || recording != null || wide != null) return
        val finished = whenStopped ?: return
        whenStopped = null
        finished()
    }

    /** The ladder steps the back camera supports. */
    private fun supportedSteps(p: ProcessCameraProvider): List<VideoMode> = supportedPhoneModes(p, ladder)

    /**
     * Starts the next file at [stepIndex] of [steps], binding the camera only for a new step. Falls down
     * the ladder if that fails.
     */
    @SuppressLint("MissingPermission") // Audio is only enabled when RECORD_AUDIO is granted (CameraService).
    @Suppress("TooGenericExceptionCaught") // CameraX reports unsupported settings and busy cameras as exceptions.
    private fun begin(stepIndex: Int) {
        // After Android's thermal shutdown no file starts again, also from a pending restart.
        val p = provider?.takeUnless { shutdown } ?: return
        val step = steps[stepIndex]
        policy.moveTo(stepIndex)
        try {
            if (ownEncoder(step) && beginOwn(p, stepIndex, step)) return
            // A full file at the same step keeps the camera open: closing and reopening it left about
            // 2 s without video between files. Only a new step (or the first file) binds again.
            val capture = bound?.takeIf { boundStep == stepIndex } ?: run {
                bound = null
                boundStep = -1
                lastFrameNanos = 0L
                logFrames()
                cameraFrames = CameraFrames(step.fps)
                threadCpu.line(SystemClock.elapsedRealtime()) // the threads' first minute starts here
                val (fresh, realtime) = bindPhoneCapture(context, p, lifecycle, step, frames)
                realtimeFrames = realtime
                bound = fresh
                boundStep = stepIndex
                fresh
            }
            val recorder = capture.output
            resultChanges = ResultChanges() // per file: a file that follows a full one starts fresh
            fileIndex++
            val name = VideoFiles.liveName(camera, fileIndex)
            val output = FileOutputOptions.Builder(File(dir, name)).setFileSizeLimit(MaxFileBytes).build()
            var pending = recorder.prepareRecording(context, output)
            if (audio) pending = pending.withAudioEnabled()
            endReason = "user"
            fileStarted = false
            var lastSync: Long? = null
            recording = pending.start(executor) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> announce(name, step, stepIndex)
                    is VideoRecordEvent.Status -> {
                        if (e.recordingStats.recordedDurationNanos > 0) fileStarted = true
                        val now = sink.nowMicros()
                        if (syncDue(now, lastSync)) {
                            lastSync = now
                            val media = e.recordingStats.recordedDurationNanos / NanosPerMicro
                            val frame = frameMicros(now, lastFrameNanos, realtimeFrames)
                            sink.synced(name, now, media, frame)
                            clips.synced(name, now, media, frame)
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        clips.finished(name, e.recordingStats.recordedDurationNanos / NanosPerMicro)
                        onFinalized(name, e)
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.w(Tag, "could not start ${step.label}", e)
            policy.failed(stepIndex)
            boundStep = -1 // whatever is bound may be the fault: the next try binds afresh
            if (stepIndex + 1 < steps.size) begin(stepIndex + 1) else fail("Camera error")
        }
    }

    /**
     * Whether [step] records through [WideRecorder]: 2.7K, which CameraX has no preset for, and 1080p·60
     * and 4K·60 (FBBD-278), unless the encoder refused one this session ([ownRefused]).
     */
    private fun ownEncoder(step: VideoMode) =
        step.size == VideoSize.QHD || (step.size in OwnSizes && step.fps == OwnFps && !ownRefused)

    /**
     * Starts [step] through [WideRecorder]; true once started. For 2.7K a refusal throws, so [begin]
     * falls down the ladder; 1080p and 4K go on with CameraX at the same step instead ([ownRefused]).
     */
    @Suppress("TooGenericExceptionCaught") // Any refusal of the own encoder hands 1080p or 4K back to CameraX.
    private fun beginOwn(p: ProcessCameraProvider, stepIndex: Int, step: VideoMode): Boolean {
        if (step.size == VideoSize.QHD) {
            beginWide(p, stepIndex, step)
            return true
        }
        return try {
            beginWide(p, stepIndex, step)
            true
        } catch (e: Exception) {
            DebugLog.w(Tag, "own encoder refused ${step.label}: CameraX records it", e)
            ownRefused = true
            false
        }
    }

    /**
     * Starts [step] through [WideRecorder], with nothing bound to CameraX. Throws when the camera has
     * no stream for it at this rate or the recorder refuses it.
     */
    private fun beginWide(p: ProcessCameraProvider, stepIndex: Int, step: VideoMode) {
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
            ?: error("no back camera")
        val target = ownTarget(context, info, step.size, step.fps, MaxFileBytes)
            ?: error("no ${step.label} stream")
        unbindOwn(p, bound)
        bound = null
        boundStep = -1
        logFrames()
        // The same camera lines as the CameraX path, so the two can be compared (FBBD-278).
        cameraFrames = CameraFrames(step.fps)
        resultChanges = ResultChanges()
        threadCpu.line(SystemClock.elapsedRealtime())
        realtimeFrames = realtimeTimestamps(info)
        fileIndex++
        val file = File(dir, VideoFiles.liveName(camera, fileIndex))
        endReason = "user"
        fileStarted = false
        DebugLog.i(Tag, "own encoder: ${target.label}, ${target.bitrate} bit/s, rotation ${target.orientation}")
        val listener = WideFiles(step, stepIndex)
        val w = WideRecorder(context, cameraIdOf(info), target, audio, realtimeFrames, listener, frames)
        w.start(file)
        wide = w
    }

    /** What [WideRecorder] reports, turned into the same file events as the CameraX path. */
    private inner class WideFiles(private val step: VideoMode, private val stepIndex: Int) : WideRecorder.Listener {
        private var name: String? = null
        private var lastSync: Long? = null

        private val ticker = object : Runnable {
            override fun run() {
                sync()
                main.postDelayed(this, WideSyncMs)
            }
        }

        override fun nextFile(): File {
            fileIndex++
            return File(dir, VideoFiles.liveName(camera, fileIndex))
        }

        override fun started(file: File) {
            name = file.name
            lastSync = null
            announce(file.name, step, stepIndex)
            main.postDelayed(ticker, WideSyncMs)
        }

        override fun handedOver(file: File, mediaMicros: Long, next: File) {
            clips.finished(file.name, mediaMicros)
            sink.stopped(file.name, sink.nowMicros(), "file_full")
            DebugLog.i(Tag, "${file.name} ended: file_full")
            main.removeCallbacks(ticker)
            started(next)
        }

        override fun lost(file: File, error: String) {
            DebugLog.w(Tag, "own encoder: ${file.name} lost: $error")
            clips.lost(file.name)
        }

        override fun ended(file: File, mediaMicros: Long, full: Boolean, error: String?, hadData: Boolean) {
            main.removeCallbacks(ticker)
            wide = null
            val announced = name == file.name
            if (announced) clips.finished(file.name, mediaMicros)
            if (error != null) DebugLog.w(Tag, "own encoder: $error")
            when {
                error != null && !hadData && !stopping && endReason == "user" -> refused(file, announced)
                announced -> fileEnded(file.name, full, error?.let { "error_wide" }, null)
                stopping -> Unit
                // Stopped while still opening (thermal shutdown): the stop's reason, not a camera fault.
                endReason != "user" -> fail(stoppedText(endReason))
                else -> fail("Camera error")
            }
            stoppedIfIdle()
        }

        /**
         * The own encoder recorded no frame of [file]. 1080p and 4K go on with CameraX at the same step;
         * 2.7K is not tried again, and the next step takes over.
         */
        private fun refused(file: File, announced: Boolean) {
            if (announced) sink.stopped(file.name, sink.nowMicros(), "error_wide")
            if (step.size in OwnSizes) {
                ownRefused = true
                begin(stepIndex)
                return
            }
            policy.failed(stepIndex)
            if (stepIndex + 1 < steps.size) begin(stepIndex + 1) else fail("Camera error")
        }

        /** About once a second, as CameraX's status events: media recorded so far and the newest frame. */
        private fun sync() {
            val w = wide
            val n = name
            val media = w?.mediaMicros
            if (w == null || n == null || media == null) return
            fileStarted = true
            val now = sink.nowMicros()
            if (syncDue(now, lastSync)) {
                lastSync = now
                val frame = frameMicros(now, w.lastFrameNanos, realtimeFrames)
                sink.synced(n, now, media, frame)
                clips.synced(n, now, media, frame)
            }
        }
    }

    /** A file is recording: the session, the clip index and the status line hear of it. */
    private fun announce(name: String, step: VideoMode, stepIndex: Int) {
        val now = sink.nowMicros()
        sink.started(name, now, step.fps)
        clips.started(name, now)
        sink.status(step.label, ok = stepIndex == 0)
        DebugLog.i(Tag, "recording $name at ${step.label}")
    }

    private fun onFinalized(name: String, e: VideoRecordEvent.Finalize) {
        recording = null
        // A full file is finished and playable: the next file starts at once, and it is no restart.
        val full = e.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        val error = !full && e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE
        fileEnded(name, full, if (error) "error_${e.error}" else null, e.cause)
        stoppedIfIdle()
    }

    /** A file ended with [error] (its reason) or none: what comes next, for either recording path. */
    private fun fileEnded(name: String, full: Boolean, error: String?, cause: Throwable?) {
        // The part-minute before the end, so every frame the file should hold is counted.
        logFrames()
        val reason = error ?: if (full && endReason == "user") "file_full" else endReason
        sink.stopped(name, sink.nowMicros(), reason)
        DebugLog.log(if (error != null) DebugLog.WARN else DebugLog.INFO, Tag, "$name ended: $reason", cause)
        when {
            // A file that finalizes late, after the service began a new camera, must not unbind that one.
            stopping -> unbindOwn(provider, bound)
            full && endReason == "user" -> begin(policy.current)
            endReason != "user" -> {
                unbindOwn(provider, bound)
                sink.status(stoppedText(endReason), ok = false)
            }
            error != null && restarts < MaxRestarts -> {
                restarts++
                boundStep = -1 // after an error the camera is closed and opened again
                sink.status("Restarting", ok = false)
                main.postDelayed({ if (!stopping) begin(policy.current) }, RetryDelayMs)
            }
            else -> fail("Camera error")
        }
    }

    /** The camera's frames since the last line, if it delivered any, and the busiest threads (FBBD-278). */
    private fun logFrames() {
        val line = cameraFrames?.line() ?: return
        DebugLog.i(Tag, line)
        threadCpu.line(SystemClock.elapsedRealtime())?.let { DebugLog.i(Tag, it) }
        wide?.timingLine()?.let { DebugLog.i(Tag, it) }
    }

    private fun fail(text: String) {
        unbindOwn(provider, bound)
        sink.status(text, ok = false)
    }

    private fun stoppedText(reason: String) = when (reason) {
        "thermal" -> "Stopped: too hot"
        "storage" -> "Stopped: storage"
        else -> "Stopped"
    }

    /**
     * Thermal and storage check; ends the current file when video must stop. Android's thermal
     * shutdown is acted on at once, also before the file holds a frame or between restarts, so the
     * file is finished before the phone powers off. The storage check waits until the file holds
     * data: a file stopped before its first frame ends with error 8.
     */
    private fun check() {
        // Read first, also after video stopped for another reason: a shutdown must reach the session log.
        val action = thermalAction()
        if (endReason != "user") return
        val recordingNow = recording != null || wide != null
        when {
            action == ThermalAction.StopVideo -> {
                shutdown = true
                endReason = "thermal"
                if (recordingNow) {
                    stopFile()
                } else {
                    unbindOwn(provider, bound)
                    sink.status(stoppedText(endReason), ok = false)
                }
            }
            fileStarted && recordingNow && StatFs(dir.path).availableBytes < MinFreeBytes -> {
                DebugLog.w(Tag, "video stops: less than 1 GB free")
                endReason = "storage"
                stopFile()
            }
        }
    }

    /** Ends the file being recorded; its end arrives as for any other stop. */
    private fun stopFile() {
        val r = recording
        if (r != null) r.stop() else wide?.stop()
    }

    private fun thermalAction(): ThermalAction {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else Thermal.NONE
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power.getThermalHeadroom(ForecastSeconds).takeUnless { it.isNaN() }
        } else {
            null
        }
        val battery = batteryC(context)
        val action = policy.onReading(status)
        thermalLog.log(status, headroom, battery, policy.current, action)
        if (status != sessionThermal) {
            sessionThermal = status
            sink.thermal(sink.nowMicros(), status, headroom)
        }
        return action
    }

    private companion object {
        const val Tag = "PhoneCamera"
    }
}

private const val TenthsPerDegree = 10f

/** The battery temperature in °C from Android's sticky battery broadcast; null if unknown. */
private fun batteryC(context: Context): Float? {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    return if (tenths == Int.MIN_VALUE) null else tenths / TenthsPerDegree
}

/**
 * Thermal readings in the debug log (FBBD-146): every status change and every stop, else one line
 * per [ThermalLogEvery] checks, so a drive log shows why video stopped.
 */
private class ThermalLog(private val steps: List<VideoMode>) {
    private var checks = 0
    private var loggedStatus = -1

    fun log(status: Int, headroom: Float?, batteryC: Float?, at: Int, action: ThermalAction) {
        checks++
        val changed = status != loggedStatus || action != ThermalAction.Keep
        if (!changed && checks < ThermalLogEvery) return
        checks = 0
        loggedStatus = status
        val decision = when (action) {
            ThermalAction.Keep -> "keep"
            ThermalAction.StopVideo -> "stop video"
        }
        val level = if (action == ThermalAction.Keep) DebugLog.INFO else DebugLog.WARN
        val text = "status=${statusName(status)} headroom=${headroom ?: "n/a"} battery=${batteryC ?: "n/a"} C " +
            "at ${label(at)}: $decision"
        DebugLog.log(level, "Thermal", text, null)
    }

    private fun label(index: Int) = steps.getOrNull(index)?.label ?: "none"

    private fun statusName(status: Int) = when (status) {
        Thermal.NONE -> "none"
        Thermal.LIGHT -> "light"
        Thermal.MODERATE -> "moderate"
        Thermal.SEVERE -> "severe"
        Thermal.CRITICAL -> "critical"
        else -> "level$status"
    }
}

/**
 * The [modes] the back camera reports it can record: its CameraX video qualities (for 2.7K, a 16:9
 * stream size between 1080p and 4K that the H.264 encoder takes at the rate, [wideFrame]), a frame-rate
 * range covering the rate, and a minimum frame duration at that quality's own frame size short
 * enough for the rate (Camera2 stream configuration), so 60 fps offered only at 1080p is not
 * offered at 4K. CameraX 1.4 still treats the rate as a target, so a phone may record at another
 * rate; [PhoneCamera] falls to the next step when binding fails. Used by the camera and by the
 * screen where Arek picks a mode, so both offer the same list.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun supportedPhoneModes(p: ProcessCameraProvider, modes: List<VideoMode>): List<VideoMode> {
    val info: CameraInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
        ?: return emptyList()
    logPhoneVideoCapabilities(info)
    val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
    val ranges = info.supportedFrameRateRanges
    val streams = Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    return modes.filter { mode ->
        val quality = phoneQuality(mode.size)
        val rate = ranges.isEmpty() || ranges.any { it.lower <= mode.fps && mode.fps <= it.upper }
        when {
            mode.size !in PhoneSizes || !rate -> false
            mode.size == VideoSize.QHD -> wideFrame(info, mode.fps) != null
            else -> quality in qualities && fastEnough(streams, QualitySelector.getResolution(info, quality), mode.fps)
        }
    }
}

/** Whether the camera can deliver [fps] at [size]; unknown (no map, no size) counts as yes. */
private fun fastEnough(streams: StreamConfigurationMap?, size: Size?, fps: Int): Boolean {
    if (streams == null || size == null) return true
    val minNanos = runCatching { streams.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
    return minNanos == null || minNanos <= 0 || minNanos <= NanosPerSecond / fps
}

private const val NanosPerSecond = 1_000_000_000L

/** The CameraX quality for a phone frame size; 2.7K has none (it records through [WideRecorder]) and maps to 1080p. */
internal fun phoneQuality(size: VideoSize): Quality = when (size) {
    VideoSize.UHD -> Quality.UHD
    VideoSize.QHD, VideoSize.FHD -> Quality.FHD
    VideoSize.HD -> Quality.HD
}

/**
 * Binds a new back-camera [VideoCapture] for [step] in place of anything bound before. Returns it and
 * whether its frame timestamps are on elapsedRealtime.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun bindPhoneCapture(
    context: Context,
    p: ProcessCameraProvider,
    lifecycle: LifecycleOwner,
    step: VideoMode,
    frames: CameraCaptureSession.CaptureCallback,
): Pair<VideoCapture<Recorder>, Boolean> {
    val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(phoneQuality(step.size)))
        .build()
    val builder = VideoCapture.Builder(recorder)
        .setTargetFrameRate(Range(step.fps, step.fps))
    val extender = Camera2Interop.Extender(builder).setSessionCaptureCallback(frames)
    // FAST processing from the first frame (FBBD-278): Arek's drives skipped single frames at capture
    // early on and none later, as if the camera dropped heavier processing on its own partway through.
    // FAST noise reduction and edges alone (1.6.0) did not stop the skips, so tonemap, aberration
    // correction, lens shading and hot-pixel correction run FAST too. Only modes the camera lists are
    // set; the log's "camera processing" line shows what it ran with.
    CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()?.let { info ->
        val camera = Camera2CameraInfo.from(info)
        val asked = FastControls.joinToString(" ") { (name, request, available) ->
            val fast = fastMode(camera.getCameraCharacteristic(available))
            fast?.let { extender.setCaptureRequestOption(request, it) }
            "$name=${if (fast == null) "default" else "FAST"}"
        }
        DebugLog.i("PhoneCamera", "asked for $asked")
    }
    val capture = builder.build()
    p.unbindAll()
    val back = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, capture)
    logVendorKeys(context, back.cameraInfo)
    return capture to realtimeTimestamps(back.cameraInfo)
}

/** The controls both recording paths ask to run FAST: log name, request key, the camera's list of modes. */
internal val FastControls = listOf(
    Triple(
        "noise_reduction",
        CaptureRequest.NOISE_REDUCTION_MODE,
        CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
    ),
    Triple("edge", CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
    Triple("tonemap", CaptureRequest.TONEMAP_MODE, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
    Triple(
        "aberration",
        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
        CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
    ),
    Triple("shading", CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_AVAILABLE_MODES),
    Triple("hot_pixel", CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
)

/** Whether the camera's frame timestamps are on elapsedRealtime (else on System.nanoTime). */
@OptIn(ExperimentalCamera2Interop::class)
internal fun realtimeTimestamps(info: CameraInfo): Boolean =
    Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

/** Unbinds [useCase] alone: a camera that ends late must not take down the one that replaced it. */
private fun unbindOwn(provider: ProcessCameraProvider?, useCase: UseCase?) {
    if (useCase != null) provider?.unbind(useCase)
}
