package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Range
import android.util.Size
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
private const val MaxFileBytes = 4_000_000_000L

/**
 * The phone's back camera, recording with CameraX in the background while the track screen stays
 * on (FBBD-21). No preview: only a VideoCapture use case is bound.
 *
 * Each recording is one file in the session's video directory (phone_0001.mp4 and on). A thermal
 * step-down or a restart after an error ends the file and starts the next one, so a file always has
 * one resolution and frame rate. Every fault ends in [VideoSink.stopped] and [VideoSink.status];
 * nothing here throws, and nothing here can reach the recorder process.
 *
 * Main thread only.
 */
class PhoneCamera(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    index: VideoIndex,
    private val audio: Boolean,
    /** The chosen mode first, then the thermal step-downs ([ladderFrom]). */
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
    private var policy = ThermalPolicy(ladder.size)

    private var recording: Recording? = null
    private var fileIndex = 0
    private var restarts = 0
    private var stopping = false

    /** What the file being finalized ends with: why it stops, and the step to start next (if any). */
    private var endReason = "user"
    private var nextStep: Int? = null

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
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { lastFrameNanos = it }
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
                policy = ThermalPolicy(steps.size)
                thermalLog = ThermalLog(steps)
                sessionThermal = null
                DebugLog.i(Tag, "steps ${steps.joinToString { it.label }} (chosen ${ladder.first().label})")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val listener = PowerManager.OnThermalStatusChangedListener { check() }
                    thermalListener = listener
                    power.addThermalStatusListener(executor, listener)
                }
                main.postDelayed(checker, CheckIntervalMs)
                // A phone already hot starts at the step it would drop to at once. On 2026-10-16 a
                // session started at 1080p with status moderate; the first check stopped that file
                // before it held a frame (error 8) and started 720p a moment later.
                when (val first = thermalAction()) {
                    ThermalAction.StopVideo -> {
                        endReason = "thermal"
                        sink.status(stoppedText(endReason), ok = false)
                    }
                    is ThermalAction.StepDown -> begin(first.to)
                    else -> begin(0)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let(power::removeThermalStatusListener)
        endReason = "user"
        nextStep = null
        val r = recording
        if (r != null) r.stop() else unbindOwn(provider, bound)
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
        val p = provider ?: return
        val step = steps[stepIndex]
        policy.moveTo(stepIndex)
        try {
            // A full file at the same step keeps the camera open: closing and reopening it left about
            // 2 s without video between files. Only a new step (or the first file) binds again.
            val capture = bound?.takeIf { boundStep == stepIndex } ?: run {
                bound = null
                boundStep = -1
                lastFrameNanos = 0L
                val (fresh, realtime) = bindPhoneCapture(p, lifecycle, step, frames)
                realtimeFrames = realtime
                bound = fresh
                boundStep = stepIndex
                fresh
            }
            val recorder = capture.output
            fileIndex++
            val name = VideoFiles.liveName(camera, fileIndex)
            val output = FileOutputOptions.Builder(File(dir, name)).setFileSizeLimit(MaxFileBytes).build()
            var pending = recorder.prepareRecording(context, output)
            if (audio) pending = pending.withAudioEnabled()
            endReason = "user"
            nextStep = null
            var lastSync: Long? = null
            recording = pending.start(executor) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> {
                        val now = sink.nowMicros()
                        sink.started(name, now)
                        clips.started(name, now)
                        sink.status(step.label, ok = stepIndex == 0)
                        DebugLog.i(Tag, "recording $name at ${step.label}")
                    }
                    is VideoRecordEvent.Status -> {
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

    private fun onFinalized(name: String, e: VideoRecordEvent.Finalize) {
        recording = null
        // A full file is finished and playable: the next file starts at once, and it is no restart.
        val full = e.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        val error = !full && e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE
        val reason = stopReason(e.error, error, full)
        sink.stopped(name, sink.nowMicros(), reason)
        DebugLog.log(if (error) DebugLog.WARN else DebugLog.INFO, Tag, "$name ended: $reason", e.cause)
        val next = nextStep
        when {
            // A file that finalizes late, after the service began a new camera, must not unbind that one.
            stopping -> unbindOwn(provider, bound)
            next != null -> begin(next)
            full && endReason == "user" -> begin(policy.current)
            endReason != "user" -> {
                unbindOwn(provider, bound)
                sink.status(stoppedText(endReason), ok = false)
            }
            error && restarts < MaxRestarts -> {
                restarts++
                boundStep = -1 // after an error the camera is closed and opened again
                sink.status("Restarting", ok = false)
                main.postDelayed({ if (!stopping) begin(policy.current) }, RetryDelayMs)
            }
            else -> fail("Camera error")
        }
    }

    /** Why a file ended, for the session's video event. */
    private fun stopReason(code: Int, error: Boolean, full: Boolean) = when {
        error -> "error_$code"
        full && endReason == "user" -> "file_full"
        else -> endReason
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

    /** Thermal and storage check; ends the current file when video must step down or stop. */
    private fun check() {
        val r = recording
        if (r == null || nextStep != null || endReason != "user") return
        val action = if (StatFs(dir.path).availableBytes < MinFreeBytes) null else thermalAction()
        when (action) {
            null -> {
                DebugLog.w(Tag, "video stops: less than 1 GB free")
                endReason = "storage"
                r.stop()
            }
            ThermalAction.Keep -> Unit
            ThermalAction.StopVideo -> {
                endReason = "thermal"
                r.stop()
            }
            is ThermalAction.StepDown -> {
                nextStep = action.to
                endReason = "thermal_step"
                r.stop()
            }
            is ThermalAction.StepUp -> {
                nextStep = action.to
                endReason = "thermal_step_up"
                r.stop()
            }
        }
    }

    private fun thermalAction(): ThermalAction {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else Thermal.NONE
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power.getThermalHeadroom(ForecastSeconds).takeUnless { it.isNaN() }
        } else {
            null
        }
        val from = policy.current
        val action = policy.onReading(sink.nowMicros(), status, headroom)
        thermalLog.log(status, headroom, from, policy.ceiling, action)
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

/**
 * Thermal readings in the debug log (FBBD-146): every status change and every step decision, else
 * one line per [ThermalLogEvery] checks, so a drive shows why and when video changed quality.
 */
private class ThermalLog(private val steps: List<VideoMode>) {
    private var checks = 0
    private var loggedStatus = -1

    fun log(status: Int, headroom: Float?, from: Int, ceiling: Int, action: ThermalAction) {
        checks++
        val changed = status != loggedStatus || action != ThermalAction.Keep
        if (!changed && checks < ThermalLogEvery) return
        checks = 0
        loggedStatus = status
        val decision = when (action) {
            ThermalAction.Keep -> "keep"
            ThermalAction.StopVideo -> "stop video"
            is ThermalAction.StepDown -> "step down to ${label(action.to)}"
            is ThermalAction.StepUp -> "step up to ${label(action.to)}"
        }
        val level = if (action == ThermalAction.Keep) DebugLog.INFO else DebugLog.WARN
        val text = "status=${statusName(status)} headroom=${headroom ?: "n/a"} at ${label(from)}: $decision " +
            "(best allowed ${label(ceiling)})"
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
 * The [modes] the back camera reports it can record: its CameraX video qualities, a frame-rate
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
    val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
    val ranges = info.supportedFrameRateRanges
    val streams = Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    return modes.filter { mode ->
        val quality = phoneQuality(mode.size)
        mode.size in PhoneSizes &&
            quality in qualities &&
            (ranges.isEmpty() || ranges.any { it.lower <= mode.fps && mode.fps <= it.upper }) &&
            fastEnough(streams, QualitySelector.getResolution(info, quality), mode.fps)
    }
}

/** Whether the camera can deliver [fps] at [size]; unknown (no map, no size) counts as yes. */
private fun fastEnough(streams: StreamConfigurationMap?, size: Size?, fps: Int): Boolean {
    if (streams == null || size == null) return true
    val minNanos = runCatching { streams.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
    return minNanos == null || minNanos <= 0 || minNanos <= NanosPerSecond / fps
}

private const val NanosPerSecond = 1_000_000_000L

/** The CameraX quality for a phone frame size; 2.7K is not a phone size and maps to 1080p. */
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
    Camera2Interop.Extender(builder).setSessionCaptureCallback(frames)
    val capture = builder.build()
    p.unbindAll()
    val back = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, capture)
    val realtime = Camera2CameraInfo.from(back.cameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    return capture to realtime
}

/** Unbinds [useCase] alone: a camera that ends late must not take down the one that replaced it. */
private fun unbindOwn(provider: ProcessCameraProvider?, useCase: UseCase?) {
    if (useCase != null) provider?.unbind(useCase)
}
