package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Range
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
    private val ladder: List<VideoStep> = DefaultLadder,
) : VideoSource {
    private val dir: File = index.videoDir
    private val clips = ClipIndexer(index)
    override val camera = CameraIds.PHONE

    private val main = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private val power = context.getSystemService(PowerManager::class.java)
    private lateinit var sink: VideoSink
    private var provider: ProcessCameraProvider? = null
    private var steps: List<VideoStep> = ladder
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

    /** The ladder steps the back camera supports: its video qualities and frame-rate ranges. */
    private fun supportedSteps(p: ProcessCameraProvider): List<VideoStep> {
        val info: CameraInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
            ?: return emptyList()
        val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        val ranges = info.supportedFrameRateRanges
        return ladder.filter { step ->
            quality(step.resolution) in qualities &&
                (ranges.isEmpty() || ranges.any { it.lower <= step.fps && step.fps <= it.upper })
        }
    }

    private fun quality(r: VideoResolution): Quality = when (r) {
        VideoResolution.UHD -> Quality.UHD
        VideoResolution.FHD -> Quality.FHD
        VideoResolution.HD -> Quality.HD
    }

    /** Binds the camera at [stepIndex] of [steps] and starts the next file. Falls down the ladder if binding fails. */
    @SuppressLint("MissingPermission") // Audio is only enabled when RECORD_AUDIO is granted (CameraService).
    @Suppress("TooGenericExceptionCaught") // CameraX reports unsupported settings and busy cameras as exceptions.
    private fun begin(stepIndex: Int) {
        val p = provider ?: return
        val step = steps[stepIndex]
        policy.reset(stepIndex)
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality(step.resolution)))
                .build()
            val capture = VideoCapture.Builder(recorder)
                .setTargetFrameRate(Range(step.fps, step.fps))
                .build()
            p.unbindAll()
            p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            fileIndex++
            val name = VideoFiles.liveName(camera, fileIndex)
            var pending = recorder.prepareRecording(context, FileOutputOptions.Builder(File(dir, name)).build())
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
        val error = e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE
        sink.stopped(name, sink.nowMicros(), if (error) "error_${e.error}" else endReason)
        val next = nextStep
        when {
            stopping -> provider?.unbindAll()
            next != null -> begin(next)
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
