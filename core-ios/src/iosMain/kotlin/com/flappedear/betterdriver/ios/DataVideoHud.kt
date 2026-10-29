package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.GForce
import com.flappedear.betterdriver.timing.overlay.HudDash
import com.flappedear.betterdriver.timing.overlay.HudRowSpec
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.LapPhase
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.StackPosition
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.lapDelta
import com.flappedear.betterdriver.timing.overlay.lapTime
import com.flappedear.betterdriver.timing.overlay.steeringText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where FlappedEar Overlays' Tech HUD (FBBD-347) puts its widgets on a picture [width] by [height]
 * pixels, as Android's TechHudPainter places them: [places] in drawing order, [scale] Overlays' scene
 * scale against 1920 by 1080, and [strips], the bands of the picture that hold a widget, top to
 * bottom (the export draws only these).
 */
class HudGeometry internal constructor(layout: TechHudLayout) {
    val width: Int = layout.width
    val height: Int = layout.height
    val scale: Float = layout.scale
    val places: List<HudPlace> = layout.boxes.map { (w, b) -> HudPlace(w, b, layout.stack[w] ?: StackPosition.Single) }
    val strips: List<HudSpan> = layout.strips.map { HudSpan(it.first, it.last) }
}

/** Widget [widget] in [box]; a temperature row's place in its stack is [stack]. */
class HudPlace(val widget: HudWidget, val box: Box, val stack: StackPosition)

/** Rows [top] to [bottom] of the picture, in pixels. */
class HudSpan(val top: Int, val bottom: Int)

/** A dot of the radar's last second: the G then, [fade] from near 0 (oldest) to near 1. */
class HudRadarDot(val g: GForce, val fade: Float)

/**
 * What the Tech HUD shows at one moment (FBBD-347), with the texts as Android's TechHudPainter writes
 * them; a value the session has none of is null, its text a dash. [trail] is the map's trail of the
 * lap so far, in runs without gaps; [radar] the G radar's last second.
 */
class HudMoment internal constructor(
    frame: OverlayFrame,
    val trail: List<List<Xy>>,
    val radar: List<HudRadarDot>,
    bestLapMicros: Long?,
    pointToPoint: Boolean,
) {
    private val car = frame.car
    private val lap = frame.lap

    val speed: String = frame.speedKmh?.roundToInt()?.toString() ?: HudDash
    val rpm: Double? = car.rpm
    val rpmText: String = (car.rpm?.roundToInt()?.toString() ?: HudDash) + " RPM"

    /** "LAP" or "RUN" (a point-to-point track, FBBD-155). */
    val lapCaption: String = if (pointToPoint) "RUN" else "LAP"
    val lapNumber: String = lap?.number?.toString() ?: HudDash
    val lapTimeText: String = lap?.let { lapTime(it.lapMicros) } ?: NoTime

    /** A single-lap export after the line (FBBD-346): the final time held, in green. */
    val lapFinished: Boolean = lap?.phase == LapPhase.Finished
    val bestText: String = bestLapMicros?.let(::lapTime) ?: HudDash

    /** The live delta to the best lap ("-0.123"); null without one. */
    val deltaText: String? = lap?.deltaMicros?.let { lapDelta(it / MicrosPerSecond) }
    val deltaAhead: Boolean = (lap?.deltaMicros ?: 0) <= 0

    /** "LAP 7" over the map; null outside a lap. */
    val mapLap: String? = lap?.let { "$lapCaption ${it.number}" }

    val throttle: Double? = car.throttlePct
    val throttleText: String = percent(car.throttlePct)
    val brake: Double? = car.brakePct
    val brakeText: String = percent(car.brakePct)

    val heartRateText: String = car.heartRate?.roundToInt()?.toString() ?: HudDash

    val g: GForce? = frame.g
    val gText: String = frame.g?.let { fixed(hypot(it.lateral, it.longitudinal), 2) } ?: HudDash
    val gLateralText: String = frame.g?.let { fixed(abs(it.lateral), 2) } ?: HudDash
    val gLongitudinalText: String = frame.g?.let { fixed(abs(it.longitudinal), 2) } ?: HudDash

    /** The wheel in degrees, positive left. */
    val steering: Double? = car.steeringDeg
    val steeringLabel: String = steeringText(car.steeringDeg)

    val position: Xy? = frame.position

    /** Row [w]'s temperature in °C; null without one, or when [w] is no row. */
    fun rowValue(w: HudWidget): Double? = when (w) {
        HudWidget.Oil -> car.oilC
        HudWidget.Gearbox -> car.gearboxC
        HudWidget.Coolant -> car.coolantC
        else -> null
    }

    fun rowText(w: HudWidget): String = rowValue(w)?.roundToInt()?.toString() ?: HudDash

    /** Row [w]'s range; its label is the row's caption. */
    fun rowSpec(w: HudWidget): HudRowSpec? = HudRowSpec.of(w)

    /** Row [w] at or over its warning: red. */
    fun rowHot(w: HudWidget): Boolean {
        val v = rowValue(w)
        val warning = HudRowSpec.of(w)?.warning
        return v != null && warning != null && v >= warning
    }

    /** Tyre [i] (front left, front right, rear left, rear right): its temperature in °C. */
    fun tyreTemp(i: Int): Double? = car.tyreTemps.getOrNull(i)

    fun tyreTempText(i: Int): String = tyreTemp(i)?.roundToInt()?.toString() ?: HudDash

    fun tyrePressureText(i: Int): String = car.tyrePressures.getOrNull(i)?.let { fixed(it, 2) } ?: HudDash

    private fun percent(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: HudDash

    private companion object {
        const val NoTime = "—:—.———"
        const val MicrosPerSecond = 1_000_000.0
    }
}

/** [points] in runs, broken at each null. */
internal fun runs(points: List<Xy?>): List<List<Xy>> {
    val out = ArrayList<List<Xy>>()
    var run = ArrayList<Xy>()
    for (p in points) {
        if (p != null) {
            run += p
        } else if (run.isNotEmpty()) {
            out += run
            run = ArrayList()
        }
    }
    if (run.isNotEmpty()) out += run
    return out
}
