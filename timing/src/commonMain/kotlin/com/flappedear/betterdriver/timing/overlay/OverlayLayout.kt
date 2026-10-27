package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in pixels, y down. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun overlaps(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** A point in pixels, y down. */
data class Px(val x: Float, val y: Float)

/**
 * Where everything goes in the overlay's band along the bottom of the video (FBBD-152), in pixels
 * of the band (its own canvas, [width] by [height], laid over the bottom of the picture).
 *
 * Left to right: speed, lap number and lap time, delta to best; at the right, a G meter and the track
 * map. Everything is sized from one unit, the band's height, so a 4K export looks the same as a
 * 1080p one: about a fifth of the picture's shorter side, less when a narrow (portrait) picture
 * needs it smaller to fit the row.
 */
class OverlayLayout private constructor(val width: Int, val height: Int) {
    private val u = height.toFloat()
    private val top = u * PadTop
    private val bottom = u * (1 - PadBottom)

    val speed: Box
    val lap: Box
    val delta: Box
    val gMeter: Box
    val map: Box

    /** Big numbers (speed, lap time, delta), label text ("KM/H", "LAP 7"), in pixels. */
    val numberSize = u * NumberSize
    val labelSize = u * LabelSize

    /** Line width of the map and the G meter's rings. */
    val stroke = max(1f, u * Stroke)

    init {
        var x = u * Margin
        fun next(w: Float): Box = Box(x, top, x + w * u, bottom).also { x += w * u + Gap * u }
        speed = next(SpeedWidth)
        lap = next(LapWidth)
        delta = next(DeltaWidth)
        val mapRight = width - u * Margin
        map = Box(mapRight - MapWidth * u, top, mapRight, bottom)
        val side = bottom - top
        val gRight = map.left - Gap * u
        gMeter = Box(gRight - side, top, gRight, bottom)
    }

    companion object {
        /** Margin at both ends and gaps between slots, in units. */
        private const val Margin = 0.3f
        private const val Gap = 0.25f
        private const val SpeedWidth = 1.3f
        private const val LapWidth = 2.1f
        private const val DeltaWidth = 1.6f
        private const val MapWidth = 1.5f
        private const val PadTop = 0.14f
        private const val PadBottom = 0.1f

        /** Every slot and gap of the row, in units: what must fit across the picture. */
        private const val RowUnits =
            2 * Margin + SpeedWidth + LapWidth + DeltaWidth + MapWidth + (1 - PadTop - PadBottom) + 4 * Gap

        private const val ShortSideShare = 0.2f
        private const val MinHeight = 24
        private const val NumberSize = 0.4f
        private const val LabelSize = 0.15f
        private const val Stroke = 0.022f

        /** The layout for a picture [videoWidth] by [videoHeight] pixels, as shown (rotation applied). */
        fun of(videoWidth: Int, videoHeight: Int): OverlayLayout {
            require(videoWidth > 0 && videoHeight > 0) { "empty picture" }
            val byHeight = min(videoWidth, videoHeight) * ShortSideShare
            val byWidth = videoWidth / RowUnits
            val h = min(byHeight, byWidth).roundToInt().coerceIn(min(MinHeight, videoHeight), videoHeight)
            return OverlayLayout(videoWidth, h)
        }
    }
}

/**
 * The track map: [outline] scaled to fit [box] less [inset] on every side, keeping its shape, north
 * up and centred. A point off the outline (the pit lane) is kept inside the box.
 */
class MapFit(outline: List<Xy>, private val box: Box, private val inset: Float) {
    private val minX = outline.minOfOrNull { it.x } ?: 0.0
    private val maxX = outline.maxOfOrNull { it.x } ?: 0.0
    private val minY = outline.minOfOrNull { it.y } ?: 0.0
    private val maxY = outline.maxOfOrNull { it.y } ?: 0.0
    private val scale: Double = run {
        val w = (box.width - 2 * inset).toDouble()
        val h = (box.height - 2 * inset).toDouble()
        val sx = if (maxX > minX) w / (maxX - minX) else Double.POSITIVE_INFINITY
        val sy = if (maxY > minY) h / (maxY - minY) else Double.POSITIVE_INFINITY
        min(sx, sy).takeIf { it.isFinite() } ?: 0.0
    }

    fun project(p: Xy): Px {
        val x = box.centerX + (p.x - (minX + maxX) / 2) * scale
        val y = box.centerY - (p.y - (minY + maxY) / 2) * scale
        return Px(
            x.toFloat().coerceIn(box.left + inset, box.right - inset),
            y.toFloat().coerceIn(box.top + inset, box.bottom - inset),
        )
    }
}

/**
 * Where the G meter's dot goes in [box] (a square): speeding up moves it up, braking down, a turn to
 * the right moves it right. [maxG] is on the outer ring; more stays on the ring.
 */
fun gDot(g: GForce, box: Box, maxG: Double = DefaultMaxG): Px {
    val r = min(box.width, box.height) / 2
    var dx = g.lateral / maxG
    var dy = -g.longitudinal / maxG
    val length = hypot(dx, dy)
    if (length > 1) {
        dx /= length
        dy /= length
    }
    return Px(box.centerX + (dx * r).toFloat(), box.centerY + (dy * r).toFloat())
}

/** Road cars on track pull about 1 g; 1.5 g on the ring leaves room for slicks and aero. */
const val DefaultMaxG = 1.5

/** At most [max] of [points], evenly spread, first and last kept: a map needs no more. */
fun <T> thin(points: List<T>, max: Int = MaxMapPoints): List<T> {
    if (points.size <= max) return points
    val step = (points.size - 1).toDouble() / (max - 1)
    return List(max) { points[(it * step).roundToInt().coerceAtMost(points.size - 1)] }
}

private const val MaxMapPoints = 400
