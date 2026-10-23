@file:Suppress("MagicNumber") // Where the text sits, in units of a hundredth of the picture's height.

package com.flappedear.betterdriver.feature.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.analysis.line.SceneFrame
import com.flappedear.betterdriver.analysis.line.SceneGround
import com.flappedear.betterdriver.analysis.line.ScenePaint
import com.flappedear.betterdriver.analysis.line.SceneShape
import com.flappedear.betterdriver.analysis.line.SceneTile
import com.flappedear.betterdriver.analysis.line.sceneFrame
import com.flappedear.betterdriver.analysis.line.underMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The colours of the perfect lap in 3D, as ARGB: the Map tab's pedal colours and the app's accent
 * for the car; [ground], [road] and [edge] are the drawn track where there is no photo (UI-201 A).
 */
data class ScenePalette(
    val ground: Int,
    val road: Int,
    val edge: Int,
    val braking: Int,
    val coasting: Int,
    val accelerating: Int,
    val car: Int,
    val text: Int,
    val textDim: Int,
    val panel: Int,
)

/** What the picture says over the road: the corner, the speed and the ghost's gap. */
internal data class SceneHud(
    val corner: String,
    val from: String,
    val speedKmh: Int?,
    val gapSeconds: Double,
    val clock: String,
)

internal fun sceneHud(scene: PerfectLapScene, seconds: Double): SceneHud {
    val c = scene.corners[scene.cornerAt(seconds)]
    val from = "${c.sessionLabel.ifEmpty { "this session" }}, lap ${c.lapNumber}"
    return SceneHud(
        corner = cornerTag(c.name),
        from = "Target from $from",
        speedKmh = scene.perfectAt(seconds).speedKmh?.roundToInt(),
        gapSeconds = scene.gapAt(seconds),
        clock = "${lineLapTime(seconds)} / ${lineLapTime(scene.perfectSeconds)}",
    )
}

/** Gap of the best lap behind the perfect lap's car, as the label beside the ghost says it. */
internal fun ghostText(gapSeconds: Double): String {
    val s = String.format(Locale.ROOT, "%.2f", abs(gapSeconds))
    return if (gapSeconds >= 0.0) "Your best lap +$s s" else "Your best lap −$s s"
}

/** A map tile [z]/[x]/[y], or null when it is not at hand. */
internal typealias TileSource3d = (z: Int, x: Int, y: Int) -> Bitmap?

/**
 * Draws the perfect lap at one moment on an Android canvas: the player and the saved video both
 * draw with this, so the video is what the player showed. [tiles] gives the satellite photo under
 * the road (UI-201 B) and [attribution] its credit; without them the road is drawn.
 */
internal class PerfectLapPainter(
    private val scene: PerfectLapScene,
    private val palette: ScenePalette,
    private val tiles: TileSource3d? = null,
    private val attribution: String? = null,
) {
    private val ground = SceneGround(scene)
    private val matrix = Matrix()
    private val src = FloatArray(CORNERS * 2)
    private val dst = FloatArray(CORNERS * 2)
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.panel }

    /** How far the camera is from the car, times the normal distance: pinched in the player, kept in the video. */
    @Volatile
    var distance = 1.0

    /** Another painter of the same, for drawing on another thread, taking its tiles from [tiles]. */
    fun copy(tiles: TileSource3d? = this.tiles) =
        PerfectLapPainter(scene, palette, tiles, attribution).also { it.distance = distance }

    /**
     * The picture at [seconds]. [controlsPx] is how much of the bottom the player's bar covers
     * (UI-204 A): the lap clock is then left to the bar and the map's credit goes above it.
     */
    fun draw(canvas: Canvas, seconds: Double, width: Int, height: Int, controlsPx: Float = 0f) {
        canvas.drawColor(palette.ground)
        val frame = sceneFrame(scene, ground, seconds, width, height, distance)
        // The drawn road first, the photo over it where a tile is at hand, then the line and the cars.
        shapes(canvas, frame.shapes.filter { it.paint.underMap })
        val photos = photo(canvas, frame.tiles)
        shapes(canvas, frame.shapes.filterNot { it.paint.underMap })
        words(canvas, frame)
        hud(canvas, frame, sceneHud(scene, seconds), width, height, clock = controlsPx <= 0f)
        if (photos > 0 && attribution != null) credit(canvas, width, height - controlsPx)
    }

    /** The map tiles on the ground, each piece pinned at its four corners; how many were drawn. */
    private fun photo(canvas: Canvas, pieces: List<SceneTile>): Int {
        val source = tiles ?: return 0
        return pieces.count { t -> source(t.z, t.x, t.y)?.let { piece(canvas, t, it) } == true }
    }

    /** Draws the part of [bmp] that [t] says on its four screen corners; false when they make no shape. */
    private fun piece(canvas: Canvas, t: SceneTile, bmp: Bitmap): Boolean {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        // Corners in the order top left, top right, bottom right, bottom left, as the scene gives them.
        val us = floatArrayOf(t.u0, t.u1, t.u1, t.u0)
        val vs = floatArrayOf(t.v0, t.v0, t.v1, t.v1)
        for (k in 0 until CORNERS) {
            src[k * 2] = us[k] * w
            src[k * 2 + 1] = vs[k] * h
            dst[k * 2] = t.xs[k]
            dst[k * 2 + 1] = t.ys[k]
        }
        if (!matrix.setPolyToPoly(src, 0, dst, 0, CORNERS)) return false
        // Each piece is cut out a pixel wider than it is, so neighbours overlap: cut exactly, the
        // edges of two pieces each half cover their pixels and the ground shows through as a seam.
        val cx = (dst[0] + dst[2] + dst[4] + dst[6]) / CORNERS
        val cy = (dst[1] + dst[3] + dst[5] + dst[7]) / CORNERS
        path.rewind()
        for (k in 0 until CORNERS) {
            val dx = dst[k * 2] - cx
            val dy = dst[k * 2 + 1] - cy
            val l = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val x = dst[k * 2] + dx / l * SEAM_PX
            val y = dst[k * 2 + 1] + dy / l * SEAM_PX
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bmp, matrix, photo)
        canvas.restore()
        return true
    }

    /** BRAKE and GAS beside their bars, white with a dark edge so they read on any ground. */
    private fun words(canvas: Canvas, frame: SceneFrame) {
        for (l in frame.labels) {
            word.textSize = l.size
            word.style = Paint.Style.STROKE
            word.strokeWidth = l.size / WORD_EDGE
            word.color = palette.panel or OPAQUE_ALPHA
            canvas.drawText(l.text, l.x, l.y, word)
            word.style = Paint.Style.FILL
            word.color = palette.text
            canvas.drawText(l.text, l.x, l.y, word)
        }
    }

    /** The map's credit, bottom right, as MapTiler's terms ask whenever its map is shown. */
    private fun credit(canvas: Canvas, width: Int, bottom: Float) {
        val u = bottom / UNITS
        text.textSize = 2.6f * u
        text.color = palette.text
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(attribution.orEmpty(), width - 2 * u, bottom - 2 * u, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun shapes(canvas: Canvas, shapes: List<SceneShape>) {
        for (s in shapes) {
            val (color, alpha) = colour(s.paint)
            fill.color = color
            fill.alpha = alpha
            path.rewind()
            path.moveTo(s.xs[0], s.ys[0])
            for (i in 1 until s.xs.size) path.lineTo(s.xs[i], s.ys[i])
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    /** A shape's colour and alpha. */
    private fun colour(paint: ScenePaint): Pair<Int, Int> = when (paint) {
        ScenePaint.Road -> palette.road to OPAQUE
        ScenePaint.RoadEdge -> palette.edge to OPAQUE
        ScenePaint.LineEdge -> palette.panel to LINE_EDGE
        ScenePaint.Braking, ScenePaint.BrakeBar -> palette.braking to OPAQUE
        ScenePaint.Coasting, ScenePaint.NoPedal -> palette.coasting to OPAQUE
        ScenePaint.Accelerating, ScenePaint.GasBar -> palette.accelerating to OPAQUE
        ScenePaint.CarHalo -> palette.car to HALO
        ScenePaint.Car -> palette.car to OPAQUE
        ScenePaint.CarDark -> darker(palette.car) to OPAQUE
        ScenePaint.GhostHalo -> palette.text to HALO
        ScenePaint.Ghost -> palette.text to GHOST
    }

    @Suppress("LongParameterList") // The picture's size and whether the clock is on it.
    private fun hud(canvas: Canvas, frame: SceneFrame, hud: SceneHud, width: Int, height: Int, clock: Boolean) {
        val u = height / UNITS
        val pad = 3 * u
        // Corner and where its target is from, top left.
        text.textSize = 5 * u
        val cornerW = maxOf(text.measureText(hud.corner), run { text.textSize = 3 * u; text.measureText(hud.from) })
        canvas.drawRoundRect(RectF(pad, pad, pad + cornerW + 4 * u, pad + 13 * u), 2 * u, 2 * u, panel)
        text.color = palette.text
        text.textSize = 5 * u
        canvas.drawText(hud.corner, pad + 2 * u, pad + 6.5f * u, text)
        text.color = palette.textDim
        text.textSize = 3 * u
        canvas.drawText(hud.from, pad + 2 * u, pad + 11 * u, text)
        // The perfect lap's speed, top right.
        val speed = hud.speedKmh?.toString() ?: "–"
        text.textSize = 8 * u
        val speedW = maxOf(text.measureText(speed), 12 * u)
        canvas.drawRoundRect(RectF(width - pad - speedW - 4 * u, pad, width - pad, pad + 13 * u), 2 * u, 2 * u, panel)
        text.color = palette.text
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(speed, width - pad - 2 * u, pad + 8.5f * u, text)
        text.textSize = 2.6f * u
        text.color = palette.textDim
        canvas.drawText("km/h", width - pad - 2 * u, pad + 11.8f * u, text)
        text.textAlign = Paint.Align.LEFT
        // The lap clock, bottom left, unless the player's bar shows it.
        if (clock) {
            text.textSize = 3.4f * u
            text.color = palette.text
            canvas.drawText(hud.clock, pad, height - pad, text)
        }
        // The best lap, labelled beside its car.
        frame.ghostLabel?.let { (x, y) ->
            text.textSize = 3.2f * u
            text.textAlign = Paint.Align.CENTER
            text.color = palette.text
            canvas.drawText(ghostText(hud.gapSeconds), x, y - u, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private companion object {
        /** The picture is laid out on a grid of this many units high. */
        const val UNITS = 100f
        const val OPAQUE = 255
        const val HALO = 70
        const val GHOST = 110
        const val LINE_EDGE = 150
        const val OPAQUE_ALPHA = 0xFF000000.toInt()
        const val WORD_EDGE = 6f
        const val CORNERS = 4
        const val SEAM_PX = 1f
        const val DARKEN = 0.35f

        fun darker(c: Int): Int {
            val r = ((c shr 16 and 0xFF) * DARKEN).toInt()
            val g = ((c shr 8 and 0xFF) * DARKEN).toInt()
            val b = ((c and 0xFF) * DARKEN).toInt()
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
