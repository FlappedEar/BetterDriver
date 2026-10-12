package com.flappedear.betterdriver.videoexport

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackColorsGreenRed
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatRunningTime
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.DefaultMaxG
import com.flappedear.betterdriver.timing.overlay.MapFit
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.OverlayLayout
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.gDot
import com.flappedear.betterdriver.timing.overlay.thin
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The data overlay of one cut of the export (FBBD-152): a band along the bottom of the picture,
 * drawn for every frame from the session data at that frame's session time. The exporter stamps
 * each frame with its time in the export; [cut] turns that into session time.
 *
 * The band is its own canvas, only as tall as [OverlayLayout] makes it, so a 4K export uploads a
 * strip, not a whole frame, per frame. Drawn on the exporter's GL thread.
 */
@OptIn(UnstableApi::class)
internal class DataOverlay(
    private val data: OverlayData,
    private val cut: VideoCut,
    private val painter: OverlayPainter,
) : CanvasOverlay(false) {
    private var layout: OverlayLayout? = null

    /** The band's bottom edge on the picture's bottom edge, centred. */
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -1f)
        .setOverlayFrameAnchor(0f, -1f)
        .build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val l = OverlayLayout.of(videoSize.width, videoSize.height)
        layout = l
        setCanvasSize(l.width, l.height)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val l = layout ?: return
        painter.draw(canvas, l, data.at(cut.sessionMicrosAt(presentationTimeUs)))
    }
}

/**
 * Draws the band (UI for FBBD-152), left to right: speed, lap and lap time, delta to the best lap
 * in the track screen's gain and loss colours; at the right a G meter and the track map with the
 * car. Light text on a dark gradient, in the app's number and label fonts, so it reads on any
 * picture. Sizes all come from [OverlayLayout].
 */
internal class OverlayPainter(fonts: OverlayFonts, outline: List<Xy>, colors: TrackColors = TrackColorsGreenRed) {
    private val outline = thin(outline)
    private val text = colors.text.toArgb()
    private val dim = colors.textDim.toArgb()
    private val gain = colors.gain.toArgb()
    private val loss = colors.loss.toArgb()
    private val best = colors.best.toArgb()
    private val shade = colors.background.toArgb()

    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.number }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.label
        letterSpacing = LabelSpacing
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scrim = Paint()

    /** Made for the layout of the cut being drawn, and kept while that cut lasts. */
    private var shaped: Shaped? = null

    private class Shaped(val layout: OverlayLayout, val map: MapFit, val track: Path, val scrim: Shader)

    fun draw(canvas: Canvas, l: OverlayLayout, f: OverlayFrame) {
        val s = shaped(l)
        scrim.shader = s.scrim
        canvas.drawRect(0f, 0f, l.width.toFloat(), l.height.toFloat(), scrim)
        number.textSize = l.numberSize
        label.textSize = l.labelSize
        speed(canvas, l.speed, f)
        lap(canvas, l.lap, f)
        delta(canvas, l.delta, f)
        gMeter(canvas, l, f)
        map(canvas, l, s, f)
    }

    private fun shaped(l: OverlayLayout): Shaped = shaped?.takeIf { it.layout === l } ?: run {
        val fit = MapFit(outline, l.map, l.stroke * MapInsetStrokes)
        val path = Path()
        outline.forEachIndexed { i, p ->
            val q = fit.project(p)
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        val gradient = LinearGradient(
            0f,
            0f,
            0f,
            l.height.toFloat(),
            intArrayOf(withAlpha(shade, 0f), withAlpha(shade, ScrimMid), withAlpha(shade, ScrimBottom)),
            floatArrayOf(0f, ScrimMidAt, 1f),
            Shader.TileMode.CLAMP,
        )
        Shaped(l, fit, path, gradient).also { shaped = it }
    }

    /** A small label above a big number, both at the left of [box], in the colours [ink] sets. */
    private fun slot(canvas: Canvas, box: Box, caption: String, value: String) {
        canvas.drawText(caption, box.left, box.top + label.textSize, label)
        canvas.drawText(value, box.left, box.bottom - box.height * NumberBottomPad, number)
    }

    private fun ink(caption: Int, value: Int) {
        label.color = caption
        number.color = value
    }

    private fun speed(canvas: Canvas, box: Box, f: OverlayFrame) {
        ink(dim, text)
        slot(canvas, box, "KM/H", f.speedKmh?.roundToInt()?.toString() ?: Dash)
    }

    private fun lap(canvas: Canvas, box: Box, f: OverlayFrame) {
        val lap = f.lap
        if (lap == null) {
            ink(dim, dim)
            slot(canvas, box, "LAP", Dash)
            return
        }
        val kind = when {
            lap.isBest -> " · BEST"
            lap.kind == LapKind.Out -> " · OUT"
            lap.kind == LapKind.In -> " · IN"
            else -> ""
        }
        ink(if (lap.isBest) best else dim, text)
        slot(canvas, box, "LAP ${lap.number}$kind", formatRunningTime(lap.lapMicros / MicrosPerMilli))
    }

    private fun delta(canvas: Canvas, box: Box, f: OverlayFrame) {
        val micros = f.lap?.deltaMicros
        val seconds = micros?.let { it / MicrosPerSecond }
        val color = when {
            seconds == null -> dim
            isDeltaGain(seconds) -> gain
            abs(seconds) < HalfHundredth -> text
            else -> loss
        }
        ink(dim, color)
        // Without laps (no saved track) the slot stays empty; on out and in laps it shows a dash.
        if (f.lap != null) slot(canvas, box, "VS BEST", seconds?.let(::formatDelta) ?: Dash)
    }

    private fun gMeter(canvas: Canvas, l: OverlayLayout, f: OverlayFrame) {
        val box = l.gMeter
        val r = box.width / 2
        line.strokeWidth = l.stroke
        line.color = withAlpha(dim, RingAlpha)
        canvas.drawCircle(box.centerX, box.centerY, r, line)
        canvas.drawCircle(box.centerX, box.centerY, r / DefaultMaxG.toFloat(), line) // 1 g
        canvas.drawLine(box.left, box.centerY, box.right, box.centerY, line)
        canvas.drawLine(box.centerX, box.top, box.centerX, box.bottom, line)
        val g = f.g ?: return
        val dot = gDot(g, box)
        fill.color = text
        canvas.drawCircle(dot.x, dot.y, r * DotShare, fill)
    }

    private fun map(canvas: Canvas, l: OverlayLayout, s: Shaped, f: OverlayFrame) {
        line.strokeWidth = l.stroke * TrackStrokes
        line.color = withAlpha(text, TrackAlpha)
        canvas.drawPath(s.track, line)
        val p = f.position ?: return
        val car = s.map.project(p)
        val r = l.height * CarShare
        fill.color = shade
        canvas.drawCircle(car.x, car.y, r * CarRimShare, fill)
        fill.color = best
        canvas.drawCircle(car.x, car.y, r, fill)
    }

    private companion object {
        const val Dash = "—"
        const val MicrosPerSecond = 1_000_000.0
        const val MicrosPerMilli = 1_000L
        const val HalfHundredth = 0.005
        const val LabelSpacing = 0.08f
        const val NumberBottomPad = 0.02f
        const val ScrimMid = 0.45f
        const val ScrimMidAt = 0.35f
        const val ScrimBottom = 0.75f
        const val RingAlpha = 0.6f
        const val TrackAlpha = 0.85f
        const val TrackStrokes = 1.6f
        const val MapInsetStrokes = 4f
        const val DotShare = 0.14f
        const val CarShare = 0.045f
        const val CarRimShare = 1.45f
    }
}

/** The app's number and label fonts (core-ui), as Android typefaces for the painter. */
internal class OverlayFonts(val number: Typeface, val label: Typeface) {
    companion object {
        /** Reads the fonts; falls back to the system's if a font cannot be read. */
        fun load(context: Context) = OverlayFonts(
            font(context, com.flappedear.betterdriver.core.ui.R.font.saira_semi_condensed_bold, Typeface.DEFAULT_BOLD),
            font(context, com.flappedear.betterdriver.core.ui.R.font.barlow_semibold, Typeface.DEFAULT),
        )

        private fun font(context: Context, id: Int, fallback: Typeface): Typeface = try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (ignored: Resources.NotFoundException) {
            fallback
        }
    }
}

/** [color] with its alpha set to [alpha], 0 to 1. */
private fun withAlpha(color: Int, alpha: Float): Int =
    (color and RgbMask) or ((alpha * MaxAlpha).roundToInt() shl AlphaShift)

private const val RgbMask = 0x00FFFFFF
private const val MaxAlpha = 255
private const val AlphaShift = 24
