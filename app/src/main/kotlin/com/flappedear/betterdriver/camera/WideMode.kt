package com.flappedear.betterdriver.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 2.7K on the phone (FBBD-239). CameraX records presets only (720p, 1080p, 4K), so 2.7K is recorded
 * by [WideRecorder] at the camera's own 16:9 stream size between 1080p and 4K nearest GoPro's 2.7K
 * width. Arek's Pixel 6 (log of 2026-10-19) streams 2688×1512 at 60 fps to an encoder.
 */
private const val GoPro27kWidth = 2704
private const val FhdWidth = 1920
private const val UhdWidth = 3840
private const val WideW = 16
private const val WideH = 9

/** The 16:9 size between 1080p and 4K nearest 2.7K's width, from a camera's encoder sizes; null if none. */
internal fun wideSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? =
    sizes.filter { (w, h) -> w * WideH == h * WideW && w in (FhdWidth + 1) until UhdWidth }
        .minByOrNull { (w, _) -> abs(w - GoPro27kWidth) }

private const val DegreesPerTurn = 360
private const val DegreesPerStep = 90

/**
 * The rotation written into the MP4 for the back camera with sensor orientation [sensorDegrees] and the
 * display at [displayDegrees], as CameraX does for a use case with no target rotation set.
 */
internal fun orientationHint(sensorDegrees: Int, displayDegrees: Int): Int =
    ((sensorDegrees - displayDegrees) % DegreesPerTurn + DegreesPerTurn) % DegreesPerTurn

/**
 * The video bitrate for [w]×[h] at [fps], scaled by pixels and frame rate from the phone's recording
 * profile [profileBps] at [pw]×[ph] and [pfps], as CameraX scales a profile to the size it records.
 */
@Suppress("LongParameterList") // Two sizes and rates: one profile, one target.
internal fun scaledBitrate(profileBps: Int, pw: Int, ph: Int, pfps: Int, w: Int, h: Int, fps: Int): Int {
    val pixels = w.toDouble() * h / (pw.toDouble() * ph)
    val rate = fps.toDouble() / pfps.coerceAtLeast(1)
    return (profileBps * pixels * rate).roundToLong().coerceIn(MinBitrate.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private const val MinBitrate = 1_000_000

/** Used when the phone has no 4K or 1080p recording profile to scale from: 2.7K at 60 fps. */
private const val FallbackBitrate = 40_000_000
private const val FallbackFps = 60

/**
 * The 2.7K frame size the back camera [info] records at [fps]: null when it has no 16:9 size between
 * 1080p and 4K that it can stream at [fps], or the H.264 encoder cannot take that size and rate.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun wideFrame(info: CameraInfo, fps: Int): Pair<Int, Int>? {
    val streams = Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val fast = streams.getOutputSizes(MediaCodec::class.java).orEmpty().filter { size ->
        val nanos = runCatching { streams.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
        nanos == null || nanos <= 0 || nanos <= NanosPerSecond / fps
    }
    return wideSize(fast.map { it.width to it.height })?.takeIf { (w, h) -> encoderTakes(w, h, fps) }
}

/** What [WideRecorder] records for 2.7K at [fps] on the back camera [info]; null when [wideFrame] is. */
@OptIn(ExperimentalCamera2Interop::class)
internal fun wideTarget(context: Context, info: CameraInfo, fps: Int, maxFileBytes: Long): WideTarget? {
    val (w, h) = wideFrame(info, fps) ?: return null
    val camera = Camera2CameraInfo.from(info)
    val sensor = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val rotation = orientationHint(sensor, displayDegrees(context))
    return WideTarget(w, h, fps, bitrate(camera.cameraId, w, h, fps), rotation, maxFileBytes)
}

/** The back camera's Camera2 id, for [WideRecorder]. */
@OptIn(ExperimentalCamera2Interop::class)
internal fun cameraIdOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

private fun encoderTakes(w: Int, h: Int, fps: Int): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
        .mapNotNull { codec ->
            runCatching { codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
        }
        .any { it.areSizeAndRateSupported(w, h, fps.toDouble()) }

@Suppress("DEPRECATION") // CamcorderProfile.get: EncoderProfiles needs API 31; minSdk is 26.
private fun bitrate(cameraId: String, w: Int, h: Int, fps: Int): Int {
    val id = cameraId.toIntOrNull()
    val profile = if (id == null) {
        null
    } else {
        listOf(CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P)
            .filter { CamcorderProfile.hasProfile(id, it) }
            .firstNotNullOfOrNull { runCatching { CamcorderProfile.get(id, it) }.getOrNull() }
    }
    return if (profile == null) {
        scaledBitrate(FallbackBitrate, w, h, FallbackFps, w, h, fps)
    } else {
        with(profile) { scaledBitrate(videoBitRate, videoFrameWidth, videoFrameHeight, videoFrameRate, w, h, fps) }
    }
}

private fun displayDegrees(context: Context): Int =
    when (context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
        Surface.ROTATION_90 -> DegreesPerStep
        Surface.ROTATION_180 -> DegreesPerTurn / 2
        Surface.ROTATION_270 -> DegreesPerTurn - DegreesPerStep
        else -> 0
    }

private const val NanosPerSecond = 1_000_000_000L
