package com.flappedear.betterdriver.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * A deeper queue between the camera and the 2.7K encoder (FBBD-246). Drawing straight into the
 * encoder's surface, the Pixel 6 allocated only 4 buffers: with ~170 ms from exposure to encoded
 * frame, the camera waited for a free one and kept ~49 of 60 fps (Arek's 2026-10-20 log on 0.4.3).
 *
 * The camera draws into [surface], an ImageReader of [buffers] encoder-ready buffers; each frame is
 * handed on to [encoderSurface] without a copy (PRIVATE format) on the relay's own thread. Frame times
 * go to the encoder on the monotonic clock, as the camera gives them to an encoder surface, so they
 * stay on the sound's clock whichever clock the sensor runs on.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class FrameRelay(encoderSurface: Surface, width: Int, height: Int, buffers: Int = Buffers) {
    private val reader = ImageReader.newInstance(
        width, height, ImageFormat.PRIVATE, buffers, HardwareBuffer.USAGE_VIDEO_ENCODE,
    )

    // A writer the encoder's surface refuses leaves nothing open: the caller then draws straight in.
    @Suppress("TooGenericExceptionCaught") // ImageWriter refuses a surface or format at runtime.
    private val writer = try {
        ImageWriter.newInstance(encoderSurface, WriterBuffers, ImageFormat.PRIVATE)
    } catch (e: Exception) {
        reader.close()
        throw e
    }
    private val thread = HandlerThread("WideRelay").apply { start() }

    private val received = AtomicLong(0)
    private val passed = AtomicLong(0)
    private val failed = AtomicLong(0)

    @Volatile private var closed = false

    /** The surface the camera draws into. */
    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ relay(it) }, Handler(thread.looper))
    }

    @Suppress("TooGenericExceptionCaught") // ImageWriter refuses a buffer with state or runtime exceptions.
    private fun relay(r: ImageReader) {
        while (!closed) {
            val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return
            received.incrementAndGet()
            try {
                val boot = SystemClock.elapsedRealtimeNanos()
                image.timestamp = monotonic(image.timestamp, boot, System.nanoTime())
                writer.queueInputImage(image)
                passed.incrementAndGet()
            } catch (e: Exception) {
                // A frame not handed on is lost, not the recording: the log counts them.
                if (failed.getAndIncrement() == 0L) DebugLog.w(Tag, "frame not handed to the encoder", e)
                runCatching { image.close() }
            }
        }
    }

    /** Frames the camera gave, handed to the encoder, and lost on the way, for the log. */
    fun counts(): String = "relay=${received.get()} relayed=${passed.get()} relay_failed=${failed.get()}"

    /**
     * Stops handing frames on; call once the camera has been told to stop, before the encoder ends its
     * stream. The camera still completes the captures in flight: frames go on to the encoder until none
     * has come for [QuietMillis] (at most [CloseMillis]), so the recording's last frames are kept.
     */
    fun close() {
        val until = SystemClock.uptimeMillis() + CloseMillis
        var seen = received.get()
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < until) {
            Thread.sleep(PollMillis)
            val now = received.get()
            if (now != seen) {
                seen = now
                quietSince = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - quietSince >= QuietMillis) {
                break
            }
        }
        closed = true
        // The relay's thread finishes the frame in hand before the reader and writer go.
        val done = CountDownLatch(1)
        Handler(thread.looper).post { done.countDown() }
        done.await(CloseMillis, TimeUnit.MILLISECONDS)
        runCatching { reader.close() }
        runCatching { writer.close() }
        thread.quitSafely()
    }

    private companion object {
        const val Tag = "WideRecorder"

        /** ~200 ms at 60 fps: room for the camera's pipeline and the encoder at once. */
        const val Buffers = 12

        /** Buffers the encoder may hold at once; more than its own queue ever takes. */
        const val WriterBuffers = 8
        const val CloseMillis = 1_000L
        const val QuietMillis = 150L
        const val PollMillis = 20L
    }
}

/**
 * [frameNanos] on the monotonic clock: a frame stamped on boot time ([bootNanos] now) is moved by the
 * clocks' difference; one already monotonic ([monoNanos] now) stays. Whichever clock it is nearer to
 * now is the one it was stamped on: a frame is under a second old, the two clocks differ by the time
 * the phone has slept.
 */
internal fun monotonic(frameNanos: Long, bootNanos: Long, monoNanos: Long): Long =
    if (abs(bootNanos - frameNanos) < abs(monoNanos - frameNanos)) frameNanos - (bootNanos - monoNanos) else frameNanos
