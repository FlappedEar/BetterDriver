@file:Suppress("MagicNumber") // Where the text sits, in units of a hundredth of the picture's height.

package com.flappedear.betterdriver.feature.analysis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.analysis.line.SceneFrame
import com.flappedear.betterdriver.analysis.line.SceneGround
import com.flappedear.betterdriver.analysis.line.ScenePaint
import com.flappedear.betterdriver.analysis.line.sceneFrame
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The colours of the perfect lap in 3D, as ARGB: the Map tab's pedal colours and the app's accent for the car. */
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

/**
 * Draws the perfect lap at one moment on an Android canvas: the player and the saved video both
 * draw with this, so the video is what the player showed.
 */
internal class PerfectLapPainter(private val scene: PerfectLapScene, private val palette: ScenePalette) {
    private val ground = SceneGround(scene)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.panel }

    /** Another painter of the same, for drawing on another thread. */
    fun copy() = PerfectLapPainter(scene, palette)

    fun draw(canvas: Canvas, seconds: Double, width: Int, height: Int) {
        canvas.drawColor(palette.ground)
        val frame = sceneFrame(scene, ground, seconds, width, height)
        shapes(canvas, frame)
        hud(canvas, frame, sceneHud(scene, seconds), width, height)
    }

    private fun shapes(canvas: Canvas, frame: SceneFrame) {
        for (s in frame.shapes) {
            val (color, alpha) = when (s.paint) {
                ScenePaint.Road -> palette.road to OPAQUE
                ScenePaint.RoadEdge -> palette.edge to OPAQUE
                ScenePaint.Braking -> palette.braking to OPAQUE
                ScenePaint.Coasting, ScenePaint.NoPedal -> palette.coasting to OPAQUE
                ScenePaint.Accelerating -> palette.accelerating to OPAQUE
                ScenePaint.CarHalo -> palette.car to HALO
                ScenePaint.Car -> palette.car to OPAQUE
                ScenePaint.CarDark -> darker(palette.car) to OPAQUE
                ScenePaint.GhostHalo -> palette.text to HALO
                ScenePaint.Ghost -> palette.text to GHOST
            }
            fill.color = color
            fill.alpha = alpha
            path.rewind()
            path.moveTo(s.xs[0], s.ys[0])
            for (i in 1 until s.xs.size) path.lineTo(s.xs[i], s.ys[i])
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun hud(canvas: Canvas, frame: SceneFrame, hud: SceneHud, width: Int, height: Int) {
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
        // The lap clock, bottom left.
        text.textSize = 3.4f * u
        text.color = palette.text
        canvas.drawText(hud.clock, pad, height - pad, text)
        // His best lap, labelled beside its car.
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
        const val DARKEN = 0.35f

        fun darker(c: Int): Int {
            val r = ((c shr 16 and 0xFF) * DARKEN).toInt()
            val g = ((c shr 8 and 0xFF) * DARKEN).toInt()
            val b = ((c and 0xFF) * DARKEN).toInt()
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
