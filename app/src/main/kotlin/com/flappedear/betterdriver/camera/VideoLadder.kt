package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize

/** The frame sizes phone video can use, largest first. 2.7K records through WideRecorder (FBBD-239). */
val PhoneSizes = listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD, VideoSize.HD)

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
    // 2.7K is offered at 60 fps only (Arek, UI-83 B, 2026-10-19), so a ladder at 30 fps skips it.
    val sizes = PhoneSizes.dropWhile { it != size }.filter { it != VideoSize.QHD || start.fps >= HighFps }
    val atRate = sizes.map { VideoMode(it, start.fps) }
    return if (start.fps > LowFps) atRate + VideoMode(sizes.last(), LowFps) else atRate
}

private const val LowFps = 30
private const val HighFps = 60

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
 * Decides when phone video stops (FBBD-46): only at Android's THERMAL_STATUS_SHUTDOWN (0x06). Arek,
 * 2026-10-19: "as long as we won't trigger 0x06, we don't care about temperature". At SHUTDOWN the
 * camera ends its file so the video is finished before Android powers the phone off; data recording
 * carries on. No other status and no battery temperature touches video.
 *
 * Its history: up to 0.4.0 video also stopped at a 60 °C battery (0.3.29) and, before 0.3.29,
 * stepped quality down on Android's earlier statuses, which cut good video on Arek's 2026-10-17
 * drives (CRITICAL at a 45 °C battery, EMERGENCY at 47 °C, both without a fault).
 *
 * The ladder ([ladderFrom]) is where the camera falls back to when a mode cannot be bound.
 */
class ThermalPolicy {
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

    /** One reading of Android's thermal [status]. */
    fun onReading(status: Int): ThermalAction =
        if (status >= Thermal.SHUTDOWN) ThermalAction.StopVideo else ThermalAction.Keep
}
