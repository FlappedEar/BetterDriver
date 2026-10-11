package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
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
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoSink
import com.flappedear.betterdriver.recorder.video.VideoSource
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.video.VideoIndex
import java.io.File

private const val NanosPerMicro = 1_000L
private const val SyncIntervalMicros = 1_000_000L
private const val CheckIntervalMs = 5_000L
private const val RetryDelayMs = 3_000L
private const val MaxRestarts = 3
private const val ForecastSeconds = 10

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
                steps = supportedSteps(p)
                if (steps.isEmpty()) {
                    sink.status("Not supported", ok = false)
                    return@addListener
                }
                policy = ThermalPolicy(steps.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val listener = PowerManager.OnThermalStatusChangedListener { check() }
                    thermalListener = listener
                    power.addThermalStatusListener(executor, listener)
                }
                main.postDelayed(checker, CheckIntervalMs)
                if (!stopping) begin(0)
            } catch (e: Exception) {
                sink.status("Camera error", ok = false)
                android.util.Log.w(Tag, "camera provider failed", e)
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
        if (r != null) r.stop() else provider?.unbindAll()
    }

    /** The ladder steps the back camera supports. */
    private fun supportedSteps(p: ProcessCameraProvider): List<VideoMode> = supportedPhoneModes(p, ladder)

    /** Binds the camera at [stepIndex] of [steps] and starts the next file. Falls down the ladder if binding fails. */
    @SuppressLint("MissingPermission") // Audio is only enabled when RECORD_AUDIO is granted (CameraService).
    @Suppress("TooGenericExceptionCaught") // CameraX reports unsupported settings and busy cameras as exceptions.
    private fun begin(stepIndex: Int) {
        val p = provider ?: return
        val step = steps[stepIndex]
        policy.moveTo(stepIndex)
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(phoneQuality(step.size)))
                .build()
            val capture = VideoCapture.Builder(recorder)
                .setTargetFrameRate(Range(step.fps, step.fps))
                .build()
            p.unbindAll()
            p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            fileIndex++
            val name = VideoFiles.liveName(camera, fileIndex)
            val output = FileOutputOptions.Builder(File(dir, name)).setFileSizeLimit(MaxFileBytes).build()
            var pending = recorder.prepareRecording(context, output)
            if (audio) pending = pending.withAudioEnabled()
            endReason = "user"
            nextStep = null
            var lastSync = Long.MIN_VALUE
            recording = pending.start(executor) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> {
                        val now = sink.nowMicros()
                        sink.started(name, now)
                        clips.started(name, now)
                        sink.status(step.label, ok = stepIndex == 0)
                    }
                    is VideoRecordEvent.Status -> {
                        val now = sink.nowMicros()
                        if (now - lastSync >= SyncIntervalMicros) {
                            lastSync = now
                            val media = e.recordingStats.recordedDurationNanos / NanosPerMicro
                            sink.synced(name, now, media)
                            clips.synced(name, now, media)
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        clips.finished(name, e.recordingStats.recordedDurationNanos / NanosPerMicro)
                        onFinalized(name, e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(Tag, "could not start ${step.label}", e)
            if (stepIndex + 1 < steps.size) begin(stepIndex + 1) else fail("Camera error")
        }
    }

    private fun onFinalized(name: String, e: VideoRecordEvent.Finalize) {
        recording = null
        // A full file is finished and playable: the next file starts at once, and it is no restart.
        val full = e.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        val error = !full && e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE
        sink.stopped(name, sink.nowMicros(), stopReason(e.error, error, full))
        val next = nextStep
        when {
            stopping -> provider?.unbindAll()
            next != null -> begin(next)
            full && endReason == "user" -> begin(policy.current)
            endReason != "user" -> {
                provider?.unbindAll()
                sink.status(stoppedText(endReason), ok = false)
            }
            error && restarts < MaxRestarts -> {
                restarts++
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
        provider?.unbindAll()
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
        }
    }

    private fun thermalAction(): ThermalAction {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else Thermal.NONE
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power.getThermalHeadroom(ForecastSeconds).takeUnless { it.isNaN() }
        } else {
            null
        }
        return policy.onReading(sink.nowMicros(), status, headroom)
    }

    private companion object {
        const val Tag = "PhoneCamera"
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
