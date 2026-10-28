package com.flappedear.betterdriver.timing.overlay

import kotlin.math.ceil
import kotlin.math.min

/**
 * The widgets of FlappedEar Overlays' built-in "Tech HUD" layout (FBBD-347), where it puts them:
 * [x], [y], [width] and [height] are shares of the picture, as Overlays' widget-templates.json
 * stores them (FlappedEar/Overlay at 29308ee, KAN-193, KAN-270, KAN-271). Listed in the template's
 * order, which is the order they are drawn in.
 *
 * Along the bottom over the bonnet: the RPM gauge with the speed, the lap, throttle and brake, three
 * temperature rows, tyres and the steering wheel; heart rate top right, the track map top left, the
 * G radar and the G readout bottom right.
 */
@Suppress("MagicNumber") // Overlays' template, copied as it is.
enum class HudWidget(val x: Float, val y: Float, val width: Float, val height: Float) {
    Tachometer(0.012f, 0.732f, 0.146f, 0.26f),
    Lap(0.166f, 0.829f, 0.12f, 0.16f),
    Pedals(0.292f, 0.829f, 0.13f, 0.16f),
    Oil(0.428f, 0.829f, 0.15f, 0.0533f),
    Gearbox(0.428f, 0.8823f, 0.15f, 0.0533f),
    Coolant(0.428f, 0.9356f, 0.15f, 0.0533f),
    Tyres(0.584f, 0.829f, 0.13f, 0.16f),
    HeartRate(0.89f, 0.015f, 0.1f, 0.16f),
    GRadar(0.852f, 0.603f, 0.14f, 0.249f),
    GReadout(0.854f, 0.876f, 0.14f, 0.114f),
    Steering(0.72f, 0.829f, 0.13f, 0.16f),
    TrackMap(0.012f, 0.015f, 0.17f, 0.3f),
    ;

    companion object {
        /** The OIL, ATF and COOLANT rows, stacked into one plate from the top. */
        val Rows = listOf(Oil, Gearbox, Coolant)
    }
}

/** Where a row sits in a stack of rows: Overlays cuts the plate's corners only at the stack's ends. */
enum class StackPosition { Single, Top, Middle, Bottom }

/**
 * The Tech HUD (FBBD-347) on a picture [width] by [height] pixels (rotation applied): each shown
 * widget's [Box], and [scale], Overlays' scene scale against a 1920 by 1080 picture, which sizes the
 * plates' padding, corner cuts and amber tabs.
 *
 * The widgets in [shown] keep their places; a temperature row the session has no value for is left
 * out and the rows below it move up, so the stack stays one plate.
 */
class TechHudLayout(val width: Int, val height: Int, shown: Set<HudWidget>) {
    val scale: Float = min(width / ReferenceWidth, height / ReferenceHeight)

    /** The shown widgets, in drawing order, with their boxes. */
    val boxes: Map<HudWidget, Box>

    /** Each shown row's place in its stack. */
    val stack: Map<HudWidget, StackPosition>

    init {
        val rows = HudWidget.Rows.filter { it in shown }
        val out = LinkedHashMap<HudWidget, Box>()
        for (w in HudWidget.entries) {
            if (w !in shown) continue
            // A row takes the place of the n-th row of the template, n its place among the shown rows.
            val place = if (w in HudWidget.Rows) HudWidget.Rows[rows.indexOf(w)] else w
            out[w] = Box(
                place.x * width,
                place.y * height,
                (place.x + place.width) * width,
                (place.y + place.height) * height,
            )
        }
        boxes = out
        stack = rows.mapIndexed { i, w ->
            w to when {
                rows.size == 1 -> StackPosition.Single
                i == 0 -> StackPosition.Top
                i == rows.lastIndex -> StackPosition.Bottom
                else -> StackPosition.Middle
            }
        }.toMap()
    }

    /**
     * The top and bottom of the picture's strips that hold a widget, in pixels, top to bottom: the
     * export draws only these, so a 4K frame uploads two strips, not the whole picture.
     */
    val strips: List<IntRange> = run {
        val spans = boxes.values.map { it.top.toInt()..min(height, ceil(it.bottom).toInt()) }
            .sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && s.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, s.last)
            } else {
                merged += s
            }
        }
        merged
    }

    private companion object {
        const val ReferenceWidth = 1920f
        const val ReferenceHeight = 1080f
    }
}

/**
 * The widgets [data] has something for (FBBD-347). The RPM gauge (it holds the speed), the lap, both G
 * widgets are always there; the rest only when the session recorded their value: Overlays leaves out
 * the steering wheel and the map that way, and an empty plate in every video helps nobody.
 */
fun hudWidgets(data: OverlayData): Set<HudWidget> {
    val v = data.carValues
    return HudWidget.entries.filter { w ->
        when (w) {
            HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout -> true
            HudWidget.Pedals -> CarValue.Accelerator in v || CarValue.Throttle in v || CarValue.Brake in v
            HudWidget.Oil -> CarValue.OilTemp in v
            HudWidget.Gearbox -> CarValue.GearboxTemp in v
            HudWidget.Coolant -> CarValue.CoolantTemp in v
            HudWidget.Tyres -> (CarValue.TyreTemps + CarValue.TyrePressures).any { it in v }
            HudWidget.HeartRate -> CarValue.HeartRate in v
            HudWidget.Steering -> CarValue.SteeringAngle in v
            HudWidget.TrackMap -> data.outline.size > 1
        }
    }.toSet()
}
