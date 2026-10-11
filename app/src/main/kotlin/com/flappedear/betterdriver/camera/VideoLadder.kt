package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize

/** The frame sizes phone video can use, largest first. 2.7K is a GoPro size only. */
val PhoneSizes = listOf(VideoSize.UHD, VideoSize.FHD, VideoSize.HD)

/**
 * The modes phone video steps down through when the phone heats up (FBBD-21), starting from the
 * mode Arek chose (FBBD-88): at each size, the chosen rate and then 30 fps, then the next smaller
 * size. So a phone without 60 fps at a size still records that size at 30 rather than a smaller
 * one. It never goes above the chosen mode. A size the phone cannot use starts the ladder at 1080p.
 */
fun ladderFrom(start: VideoMode): List<VideoMode> {
    val size = start.size.takeIf { it in PhoneSizes } ?: VideoSize.FHD
    val rates = if (start.fps > LowFps) listOf(start.fps, LowFps) else listOf(start.fps)
    return PhoneSizes.dropWhile { it != size }.flatMap { s -> rates.map { VideoMode(s, it) } }
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
}

/** What the camera should do after a thermal reading. */
sealed interface ThermalAction {
    data object Keep : ThermalAction
    data class StepDown(val to: Int) : ThermalAction
    data object StopVideo : ThermalAction
}

/**
 * Decides when phone video steps down. It never steps back up during a session, so quality does
 * not oscillate and the file count stays small.
 *
 * - Headroom forecast at or above [headroomLimit] (1.0 is where Android starts severe throttling)
 *   or status MODERATE: one step down, at most once per [minStepMicros].
 * - SEVERE: one step down at once, whatever the interval.
 * - CRITICAL or worse: video stops; data recording carries on.
 * - Already at the last step and still hot: video stops.
 */
class ThermalPolicy(
    private val steps: Int,
    private val minStepMicros: Long = DefaultMinStepMicros,
    private val headroomLimit: Float = DefaultHeadroomLimit,
) {
    var current = 0
        private set
    private var lastStepAt: Long? = null

    /** Starts from [index] (the first supported step), with no step taken yet. */
    fun reset(index: Int) {
        current = index
        lastStepAt = null
    }

    fun onReading(nowMicros: Long, status: Int, headroom: Float?): ThermalAction {
        val hot = status >= Thermal.MODERATE || (headroom != null && headroom >= headroomLimit)
        val urgent = status >= Thermal.SEVERE
        val due = lastStepAt?.let { nowMicros - it >= minStepMicros } ?: true
        return when {
            status >= Thermal.CRITICAL -> ThermalAction.StopVideo
            !hot || !(urgent || due) -> ThermalAction.Keep
            current >= steps - 1 -> ThermalAction.StopVideo
            else -> {
                current++
                lastStepAt = nowMicros
                ThermalAction.StepDown(current)
            }
        }
    }

    private companion object {
        const val DefaultMinStepMicros = 60_000_000L
        const val DefaultHeadroomLimit = 0.9f
    }
}
