package com.flappedear.betterdriver.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.camera.CameraTestState.Companion.PartMinutes
import com.flappedear.betterdriver.camera.CameraTestState.Companion.Parts
import com.flappedear.betterdriver.camera.CameraTestState.Status
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.setup.VideoSize
import java.io.File

/**
 * The camera test (FBBD-278, UI-150..152 A), Arek's plan of 2026-10-21: 1080p · 60 twice in a row
 * with the same settings, sound included, [PartMinutes] minutes each. Part 1 writes the MP4 as a
 * session does; part 2 runs both encoders and hands each buffer straight back, with no MP4. The log
 * gets the same lines a session's video gets once a minute (camera frames, threads, encoder timing),
 * so the two parts show whether writing the file holds the camera back. Part 1's video is deleted.
 *
 * Runs on the main thread of the camera's process; [ended] once it has finished, stopped or failed.
 */
internal class CameraTest(private val context: Context, private val audio: Boolean, private val ended: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.cacheDir, "camera-test")
    private val threadCpu = ThreadCpu()
    private val results = arrayOfNulls<TestPart>(Parts)

    private var cameraId = ""
    private var target: WideTarget? = null
    private var realtime = false
    private var part = 0
    private var fileIndex = 0
    private var recorder: WideRecorder? = null
    private var frames: CameraFrames? = null
    private var partEnds = 0L
    private var stopReason: String? = null
    private var over = false

    private val endPart = Runnable { recorder?.stop() }

    private val minute = object : Runnable {
        override fun run() {
            logMinute()
            main.postDelayed(this, MillisPerMinute)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            write(Status.Running)
            main.postDelayed(this, TickMs)
        }
    }

    @Suppress("TooGenericExceptionCaught") // CameraX reports a missing camera service as any runtime exception.
    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (over) return@addListener
            try {
                val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(future.get().availableCameraInfos).firstOrNull()
                target = info?.let { ownTarget(context, it, VideoSize.FHD, Fps, MaxFileBytes) }
                if (info == null || target == null) {
                    end(Status.Failed, "this phone has no 1080p · 60 stream for the encoder")
                } else {
                    cameraId = cameraIdOf(info)
                    realtime = realtimeTimestamps(info)
                    begin(1)
                }
            } catch (e: Exception) {
                DebugLog.w(Tag, "camera test: no camera", e)
                end(Status.Failed, "the camera could not be opened")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Ends the test early; the part that runs finishes its file first. */
    fun stop(reason: String) {
        if (over || stopReason != null) return
        stopReason = reason
        DebugLog.i(Tag, "camera test: stopping, $reason")
        main.removeCallbacks(endPart)
        recorder?.stop() ?: end(Status.Stopped, reason)
    }

    @Suppress("TooGenericExceptionCaught") // The encoder or camera refused this mode.
    private fun begin(n: Int) {
        val t = target ?: return
        part = n
        dir.mkdirs()
        frames = CameraFrames(t.fps)
        threadCpu.line(SystemClock.elapsedRealtime())
        val mux = n == 1
        DebugLog.i(
            Tag,
            "camera test part $n of $Parts: ${t.label} ${if (mux) "with MP4" else "without MP4"}, " +
                "${t.bitrate} bit/s, sound ${if (audio) "on" else "off"}, $PartMinutes min",
        )
        val w = WideRecorder(context, cameraId, t, audio, realtime, Files(), Captures(), mux)
        try {
            w.start(nextFile())
        } catch (e: Exception) {
            DebugLog.w(Tag, "camera test part $n did not start", e)
            end(Status.Failed, "part $n did not start: ${e.javaClass.simpleName}")
            return
        }
        recorder = w
        partEnds = System.currentTimeMillis() + PartMinutes * MillisPerMinute
        main.postDelayed(endPart, PartMinutes * MillisPerMinute)
        main.postDelayed(minute, MillisPerMinute)
        main.post(tick)
    }

    private fun nextFile(): File {
        fileIndex++
        return File(dir, "part${part}_$fileIndex.mp4")
    }

    /** The part's camera, threads and encoder lines for the minute, as a session's video logs them. */
    private fun logMinute() {
        frames?.line()?.let { DebugLog.i(Tag, it) }
        threadCpu.line(SystemClock.elapsedRealtime())?.let { DebugLog.i(Tag, it) }
        recorder?.timingLine()?.let { DebugLog.i(Tag, it) }
    }

    /** The part's recording ended, as planned or not: the next part, or the end of the test. */
    private fun partEnded(error: String?) {
        main.removeCallbacks(endPart)
        main.removeCallbacks(minute)
        main.removeCallbacks(tick)
        logMinute()
        recorder = null
        val result = frames?.total()
        results[part - 1] = result
        DebugLog.i(
            Tag,
            "camera test part $part of $Parts ${if (part == 1) "with MP4" else "without MP4"}: " +
                (result?.text ?: "no frames") + error?.let { " error=$it" }.orEmpty(),
        )
        val reason = stopReason
        when {
            error != null -> end(Status.Failed, "part $part: $error")
            reason != null -> end(Status.Stopped, reason)
            part < Parts -> {
                write(Status.Running)
                // The camera is closed on the recorder's thread; the next part opens it again.
                main.postDelayed({ if (!over) begin(part + 1) }, NextPartMs)
            }
            else -> end(Status.Done, null)
        }
    }

    private fun end(status: Status, note: String?) {
        if (over) return
        over = true
        main.removeCallbacksAndMessages(null)
        write(status, note)
        DebugLog.i(
            Tag,
            "camera test ${status.name.lowercase()}: " + results.mapIndexed { i, r ->
                "part ${i + 1} ${r?.text ?: "n/a"}"
            }.joinToString(", ") + note?.let { ", $it" }.orEmpty(),
        )
        dir.deleteRecursively()
        ended()
    }

    private fun write(status: Status, note: String? = null) {
        // While a part runs, its result so far.
        val now = if (status == Status.Running) frames?.total() else null
        val first = results[0] ?: now.takeIf { part == 1 }
        val second = results[1] ?: now.takeIf { part == 2 }
        CameraTestState.write(context, CameraTestState(status, part.coerceAtLeast(1), partEnds, first, second, note))
    }

    /** Part 1's files: kept only until the test ends ([end] deletes them). */
    private inner class Files : WideRecorder.Listener {
        override fun nextFile(): File = this@CameraTest.nextFile()

        override fun started(file: File) = Unit

        override fun handedOver(file: File, mediaMicros: Long, next: File) {
            file.delete()
        }

        override fun lost(file: File, error: String) = Unit

        override fun ended(file: File, mediaMicros: Long, full: Boolean, error: String?, hadData: Boolean) {
            partEnded(error)
        }
    }

    /** The camera's results, into the part's minute lines and its total. */
    private inner class Captures : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val sensor = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            frames?.completed(
                sensor,
                result.get(CaptureResult.SENSOR_FRAME_DURATION),
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { it.lower to it.upper },
                result.frameNumber,
            )
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            frames?.failed()
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
            frames?.bufferLost()
        }
    }

    private companion object {
        const val Tag = "CameraTest"
        const val Fps = 60
        const val MillisPerMinute = 60_000L
        const val TickMs = 2_000L
        const val NextPartMs = 1_000L
    }
}
