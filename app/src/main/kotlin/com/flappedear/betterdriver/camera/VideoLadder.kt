package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize

/** The frame sizes phone video can use, largest first. 2.7K is a GoPro size only. */
val PhoneSizes = listOf(VideoSize.UHD, VideoSize.FHD, VideoSize.HD)

/**
 * The modes phone video falls back through when the camera cannot record a mode (FBBD-21), starting
 * from the mode Arek chose (FBBD-88). Heat no longer steps down through it (ThermalPolicy). Frame
 * rate comes before picture quality (Arek, 2026-10-13): every
 * smaller size at the chosen rate first, each with the lower bitrate CameraX gives that size, and
 * only then the smallest size at 30 fps. It never goes above the chosen mode. A size the phone
 * cannot use starts the ladder at 1080p.
 */
fun ladderFrom(start: VideoMode): List<VideoMode> {
    val size = start.size.takeIf { it in PhoneSizes } ?: VideoSize.FHD
    val sizes = PhoneSizes.dropWhile { it != size }
    val atRate = sizes.map { VideoMode(it, start.fps) }
    return if (start.fps > LowFps) atRate + VideoMode(sizes.last(), LowFps) else atRate
}

private const val LowFps = 30

/**
 * Android's thermal status (PowerManager.THERMAL_STATUS_*), as plain numbers so this stays testable
 * on the JVM.
 */
object Thermal {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6
}

/** What the camera should do after a thermal reading. */
sealed interface ThermalAction {
    data object Keep : ThermalAction
    data object StopVideo : ThermalAction
}

/**
 * Decides when phone video stops for heat (FBBD-46). It no longer steps quality down or up.
 *
 * Arek's heat stress test of 2026-10-17 (FBBD-231): Android reported SEVERE after 2 min and
 * CRITICAL from 6.5 min, with the battery at only 41 to 45 °C, while the Pixel 6 recorded 4K · 60
 * for 55 min without a fault. The old policy (step down at MODERATE or a forecast of 0.95, stop at
 * CRITICAL) would have stopped video at 6.5 min. Arek: "Critical would be around 60, not 45", and
 * "we don't need this stepping". So video keeps the chosen mode and only stops, for the rest of the
 * session (data recording carries on), when:
 *
 * - the battery reaches [stopC], or
 * - Android's thermal status is SHUTDOWN (or, with the battery temperature unknown, CRITICAL).
 *
 * Not at EMERGENCY: on Arek's 2026-10-17 afternoon drive Android reported EMERGENCY for 12 min at a
 * 46.6 to 47.5 °C battery, while the Pixel 6 recorded 1080p · 60 without a fault or a shutdown.
 * Stopping there would have lost the last 27 min of video.
 *
 * The ladder ([ladderFrom]) is still where the camera falls back to when a mode cannot be bound.
 *
 * With [ignoreHeat] (Settings › Diagnostics › Ignore phone temperature, FBBD-231) every reading keeps
 * the chosen mode, even SHUTDOWN: Arek's heat stress test, where only the phone itself may throttle.
 */
class ThermalPolicy(
    private val ignoreHeat: Boolean = false,
    private val stopC: Float = StopBatteryC,
) {
    /** The ladder step the camera records at; it changes only when a mode cannot be bound. */
    var current = 0
        private set

    /** The best step this session may still use: below any step that could not be bound. */
    var ceiling = 0
        private set

    /** Moves to [index] when the camera binds a step: the first one, or a fallback after a bind failure. */
    fun moveTo(index: Int) {
        current = index
    }

    /** A step that could not be bound is never used again. */
    fun failed(index: Int) {
        if (index >= ceiling) ceiling = index + 1
    }

    /** One reading: Android's thermal [status] and the battery temperature [batteryC] (null if unknown). */
    fun onReading(status: Int, batteryC: Float?): ThermalAction {
        if (ignoreHeat) return ThermalAction.Keep
        val tooHot = if (batteryC != null) batteryC >= stopC else status >= Thermal.CRITICAL
        return if (tooHot || status >= Thermal.SHUTDOWN) ThermalAction.StopVideo else ThermalAction.Keep
    }
}

/** The battery temperature that stops phone video (Arek 2026-10-17: "Critical would be around 60"). */
const val StopBatteryC = 60f
