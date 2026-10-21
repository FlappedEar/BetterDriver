package com.flappedear.betterdriver.camera

import java.util.Locale
import kotlin.math.roundToLong

/** Camera2's FAST value for noise reduction and edge modes. */
private const val FastMode = 1

/** FAST when the camera lists it among its [available] noise-reduction or edge modes, else null (FBBD-278). */
internal fun fastMode(available: IntArray?): Int? = FastMode.takeIf { available?.contains(it) == true }

/**
 * What the phone camera delivered over the last minute, for the debug log (FBBD-278): Arek's
 * 2026-10-21 1080p·60 file held 56.6 fps, and the log could not say whether the camera slowed down,
 * skipped frames, lost buffers or the encoder dropped them. One line a minute says which.
 *
 * Fed from the camera's capture callbacks (any thread); [line] is read from the main thread.
 */
class CameraFrames(fps: Int) {
    /** The frame interval the camera was set to, in nanoseconds. */
    private val intervalNanos = NanosPerSecond / fps.coerceAtLeast(1)

    private var completed = 0L
    private var failed = 0L
    private var bufferLost = 0L

    /** Frames the sensor timestamps skip: a gap of n intervals holds n-1 missing frames. */
    private var skipped = 0L
    private var maxGapNanos = 0L

    /** The time between consecutive frames this minute, and how many such steps it holds. */
    private var spanNanos = 0L
    private var steps = 0L
    private var lastNanos = 0L
    private var minFrameNanos = Long.MAX_VALUE
    private var maxFrameNanos = 0L
    private var maxExposureNanos = 0L
    private var aeRange: String? = null

    /** The camera's processing modes last seen ([processing]); -1 before the first frame. */
    private var lastModes = -1

    /**
     * A capture finished with sensor timestamp [sensorNanos], its frame duration and exposure time
     * when the result has them, and the auto-exposure frame-rate range it ran with.
     */
    @Synchronized
    fun completed(sensorNanos: Long, frameNanos: Long?, exposureNanos: Long?, ae: Pair<Int, Int>?) {
        completed++
        if (lastNanos > 0 && sensorNanos > lastNanos) {
            val gap = sensorNanos - lastNanos
            spanNanos += gap
            steps++
            maxGapNanos = maxOf(maxGapNanos, gap)
            val intervals = (gap.toDouble() / intervalNanos).roundToLong()
            if (intervals > 1) skipped += intervals - 1
        }
        if (sensorNanos > lastNanos) lastNanos = sensorNanos
        frameNanos?.takeIf { it > 0 }?.let {
            minFrameNanos = minOf(minFrameNanos, it)
            maxFrameNanos = maxOf(maxFrameNanos, it)
        }
        exposureNanos?.let { maxExposureNanos = maxOf(maxExposureNanos, it) }
        ae?.let { (lower, upper) -> aeRange = "[$lower,$upper]" }
    }

    /**
     * The processing a capture ran with: noise reduction [noise], [edge] enhancement, video
     * stabilization [stabilization] and optical stabilization [ois], as Camera2 result values (null
     * when the result has none). Returns a line when they differ from the last capture's, else null.
     * Arek's 2026-10-21 12:52 drive skipped single frames for its first 11 minutes and none after,
     * at the same exposure and frame duration (FBBD-278); a mode the camera switches on its own
     * would show here at the minute the skips stop.
     */
    @Synchronized
    fun processing(noise: Int?, edge: Int?, stabilization: Int?, ois: Int?): String? {
        val modes = ((code(noise) * ModeBase + code(edge)) * ModeBase + code(stabilization)) * ModeBase + code(ois)
        if (modes == lastModes) return null
        lastModes = modes
        return "camera processing: noise_reduction=${name(noise, NoiseModes)} edge=${name(edge, EdgeModes)} " +
            "video_stabilization=${name(stabilization, StabilizationModes)} ois=${name(ois, OisModes)}"
    }

    /** A capture failed: Camera2's onCaptureFailed. */
    @Synchronized
    fun failed() {
        failed++
    }

    /** A captured frame never reached the encoder's surface: Camera2's onCaptureBufferLost. */
    @Synchronized
    fun bufferLost() {
        bufferLost++
    }

    /**
     * The minute's line, and a fresh minute; null when the camera delivered nothing since the last one.
     * "camera frames: 3392 in 60.0 s = 56.5 fps, skipped=208 failed=0 buffer_lost=0 max_gap_ms=50.0
     * frame_ms=16.7-16.7 exposure_max_ms=8.3 ae=[60,60]": captures completed, their sensor time span,
     * frames missing from the sensor timestamps, failed captures, frames lost before the encoder.
     */
    @Synchronized
    fun line(): String? {
        if (completed == 0L && failed == 0L && bufferLost == 0L) return null
        val rate = if (steps > 0 && spanNanos > 0) {
            String.format(Locale.US, "%.1f", steps * NanosPerSecond.toDouble() / spanNanos)
        } else {
            "n/a"
        }
        val frame = if (maxFrameNanos > 0) "${ms(minFrameNanos)}-${ms(maxFrameNanos)}" else "n/a"
        val text = "camera frames: $completed in ${seconds(spanNanos)} s = $rate fps, skipped=$skipped " +
            "failed=$failed buffer_lost=$bufferLost max_gap_ms=${ms(maxGapNanos)} frame_ms=$frame " +
            "exposure_max_ms=${ms(maxExposureNanos)} ae=${aeRange ?: "n/a"}"
        completed = 0
        failed = 0
        bufferLost = 0
        skipped = 0
        maxGapNanos = 0
        // The next minute's first step starts at this minute's last frame, so no time falls between lines.
        spanNanos = 0
        steps = 0
        minFrameNanos = Long.MAX_VALUE
        maxFrameNanos = 0
        maxExposureNanos = 0
        return text
    }

    private fun code(mode: Int?) = (mode ?: -1) + 1

    private fun name(mode: Int?, names: List<String>) = mode?.let { names.getOrNull(it) ?: "mode$it" } ?: "n/a"

    private fun ms(nanos: Long) = String.format(Locale.US, "%.1f", nanos / NanosPerMilli)

    private fun seconds(nanos: Long) = String.format(Locale.US, "%.1f", nanos / NanosPerSecond.toDouble())

    private companion object {
        const val NanosPerSecond = 1_000_000_000L
        const val NanosPerMilli = 1_000_000.0

        /** Room for each mode value (Camera2 uses 0 to 4, vendors may add more) and "none" in one key. */
        const val ModeBase = 64

        // Camera2's value order for each control (CaptureRequest constants).
        val NoiseModes = listOf("OFF", "FAST", "HIGH_QUALITY", "MINIMAL", "ZERO_SHUTTER_LAG")
        val EdgeModes = listOf("OFF", "FAST", "HIGH_QUALITY", "ZERO_SHUTTER_LAG")
        val StabilizationModes = listOf("OFF", "ON", "PREVIEW_STABILIZATION")
        val OisModes = listOf("OFF", "ON")
    }
}
