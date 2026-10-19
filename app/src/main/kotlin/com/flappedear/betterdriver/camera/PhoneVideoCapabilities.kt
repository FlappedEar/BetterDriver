package com.flappedear.betterdriver.camera

import android.hardware.camera2.CameraCharacteristics
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

/** Frame sizes between 1080p and 4K that a 2.7K or 1440p phone mode would use (FBBD-239). */
@Suppress("MagicNumber") // The frame sizes themselves.
internal val MiddleSizes = listOf(2704 to 1520, 2560 to 1440)

private val logged = AtomicBoolean(false)

/**
 * Writes the back camera's video capabilities to the debug log once per run (FBBD-239): every frame
 * size it can stream to an encoder with its highest frame rate, whether the phone has a 1440p
 * recording profile, and whether its H.264 and HEVC encoders take the [MiddleSizes] at 60 fps. Tells
 * from Arek's log whether a mode between 1080p and 4K is possible. Never throws.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Suppress("TooGenericExceptionCaught") // Diagnostics only: a failing query must not stop video.
internal fun logPhoneVideoCapabilities(info: CameraInfo) {
    if (!logged.compareAndSet(false, true)) return
    try {
        val camera = Camera2CameraInfo.from(info)
        val streams = camera.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = streams?.getOutputSizes(MediaCodec::class.java).orEmpty().map { size ->
            val nanos = runCatching { streams?.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
            size.width to size.height to maxFps(nanos)
        }
        DebugLog.i("VideoCaps", "back camera encoder sizes: ${encoderSizesLine(sizes)}")
        val qhd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera.cameraId.toIntOrNull()?.let { CamcorderProfile.hasProfile(it, CamcorderProfile.QUALITY_QHD) }
        } else {
            null
        }
        DebugLog.i("VideoCaps", "1440p recording profile: ${qhd ?: "unknown"}")
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            DebugLog.i("VideoCaps", "$mime encoder at 60 fps: ${encoderLine(codecs, mime)}")
        }
    } catch (e: Exception) {
        DebugLog.w("VideoCaps", "could not read the back camera's video capabilities", e)
    }
}

/** Frames per second a minimum frame duration allows; 0 when unknown. */
internal fun maxFps(minFrameNanos: Long?): Int =
    if (minFrameNanos == null || minFrameNanos <= 0) 0 else Math.round(NanosPerSecond.toDouble() / minFrameNanos).toInt()

/** "3840x2160@60 2704x1520@60 …", largest first; "none" when the camera lists none. */
internal fun encoderSizesLine(sizes: List<Pair<Pair<Int, Int>, Int>>): String =
    sizes.sortedByDescending { (wh, _) -> wh.first.toLong() * wh.second }
        .joinToString(" ") { (wh, fps) -> "${wh.first}x${wh.second}@${if (fps > 0) fps else "?"}" }
        .ifEmpty { "none" }

private fun encoderLine(codecs: MediaCodecList, mime: String): String {
    val video = codecs.codecInfos.filter { it.isEncoder && mime in it.supportedTypes }
        .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
    if (video.isEmpty()) return "no encoder"
    return MiddleSizes.joinToString(" ") { (w, h) ->
        val ok = video.any { it.areSizeAndRateSupported(w, h, MiddleFps) }
        "${w}x$h=${if (ok) "yes" else "no"}"
    }
}

private const val MiddleFps = 60.0
private const val NanosPerSecond = 1_000_000_000L
