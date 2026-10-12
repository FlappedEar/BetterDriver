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
    data class StepUp(val to: Int) : ThermalAction
    data object StopVideo : ThermalAction
}

/**
 * Decides when phone video steps down, and back up once the phone has cooled (FBBD-147).
 *
 * - Headroom forecast at or above [headroomLimit] (1.0 is where Android starts severe throttling)
 *   or status MODERATE: one step down. The next one waits [settleMicros] after the last change, so
 *   the phone has time to cool at the lower mode before it drops again.
 * - SEVERE: one step down at once, whatever the interval.
 * - CRITICAL or worse: video stops; data recording carries on.
 * - Already at the last step and still hot: video stops.
 * - Cool (status LIGHT or less and headroom below [coolHeadroom], or unknown) for [recoverMicros]
 *   since both the last change and the last warm reading: one step back up. A step that gets hot
 *   again after being stepped up to is not tried again this session, so quality cannot keep
 *   swinging between two steps.
 */
class ThermalPolicy(
    private val steps: Int,
    private val settleMicros: Long = DefaultSettleMicros,
    private val recoverMicros: Long = DefaultRecoverMicros,
    private val headroomLimit: Float = DefaultHeadroomLimit,
    private val coolHeadroom: Float = DefaultCoolHeadroom,
) {
    var current = 0
        private set

    /** The best step this session may still step up to. */
    var ceiling = 0
        private set
    private var lastChangeAt: Long? = null
    private var lastChangeUp = false
    private var lastWarmAt: Long? = null

    /**
     * Moves to [index] when the camera binds a step: the first one, a fallback after a bind failure,
     * or the step a step change chose. The time of the last change is kept, so a rebind does not
     * make the next change due at once.
     */
    fun moveTo(index: Int) {
        current = index
    }

    /** A step that could not be bound is never stepped up to again. */
    fun failed(index: Int) {
        if (index >= ceiling) ceiling = index + 1
    }

    fun onReading(nowMicros: Long, status: Int, headroom: Float?): ThermalAction {
        val hot = status >= Thermal.MODERATE || (headroom != null && headroom >= headroomLimit)
        val cool = status <= Thermal.LIGHT && (headroom == null || headroom < coolHeadroom)
        if (!cool) lastWarmAt = nowMicros
        val sinceChange = lastChangeAt?.let { nowMicros - it }
        return when {
            status >= Thermal.CRITICAL -> ThermalAction.StopVideo
            hot -> stepDown(nowMicros, urgent = status >= Thermal.SEVERE, sinceChange)
            cool && current > ceiling && recovered(nowMicros, sinceChange) -> {
                current--
                change(nowMicros, up = true)
                ThermalAction.StepUp(current)
            }
            else -> ThermalAction.Keep
        }
    }

    /** Cool long enough to step up: since the last change, and since the last warm reading. */
    private fun recovered(nowMicros: Long, sinceChange: Long?): Boolean =
        sinceChange != null && sinceChange >= recoverMicros &&
            lastWarmAt.let { it == null || nowMicros - it >= recoverMicros }

    private fun stepDown(nowMicros: Long, urgent: Boolean, sinceChange: Long?): ThermalAction = when {
        // The interval only spaces step-downs: hot at the last step stops video at once.
        current >= steps - 1 -> ThermalAction.StopVideo
        !urgent && sinceChange != null && sinceChange < settleMicros -> ThermalAction.Keep
        else -> {
            // Hot again after stepping up: that step is too much for this drive.
            if (lastChangeUp) ceiling = current + 1
            current++
            change(nowMicros, up = false)
            ThermalAction.StepDown(current)
        }
    }

    private fun change(nowMicros: Long, up: Boolean) {
        lastChangeAt = nowMicros
        lastChangeUp = up
    }

    private companion object {
        const val DefaultSettleMicros = 180_000_000L
        const val DefaultRecoverMicros = 300_000_000L
        const val DefaultHeadroomLimit = 0.9f
        const val DefaultCoolHeadroom = 0.7f
    }
}
