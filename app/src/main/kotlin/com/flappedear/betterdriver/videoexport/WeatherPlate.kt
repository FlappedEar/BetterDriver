@file:Suppress("MagicNumber") // The plate's own proportions, as the mockup (UI-320 B).

package com.flappedear.betterdriver.videoexport

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.HudWeather
import com.flappedear.betterdriver.timing.overlay.IconShape
import com.flappedear.betterdriver.timing.overlay.WeatherColors
import com.flappedear.betterdriver.timing.overlay.skyIcon
import com.flappedear.betterdriver.timing.overlay.windArrow
import kotlin.math.max

/**
 * The Tech HUD's weather plate (FBBD-471, UI-320 B), inside the plate [TechHudPainter] has drawn:
 * the sky and "9°C", the track surface in its colour, then the wind's arrow and speed and the
 * humidity. Each line is left out when the weather has none of it. Sizes are shares of the plate's
 * height, as the iOS painter draws it.
 */
internal class WeatherPlate(private val semibold: Paint, private val bold: Paint) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    fun draw(c: Canvas, b: Box, w: HudWeather) {
        val h = b.height
        val gap = h * Gap
        val rowH = max(h * IconShare, setText(bold, h * TempShare, Text))
        val surface = w.surface
        val labelH = if (surface != null) setText(semibold, h * SmallShare, Dim, h * Spacing) else 0f
        val more = w.windText != null || w.humidityText != null
        val moreH = if (more) setText(semibold, h * SmallShare, Text) else 0f
        val total = rowH + (if (surface != null) gap + labelH else 0f) + (if (more) gap + moreH else 0f)
        var top = b.centerY - total / 2
        skyAndAir(c, b, w, top, rowH)
        top += rowH
        if (surface != null) {
            top += gap
            setText(semibold, h * SmallShare, WeatherColors.of(surface), h * Spacing)
            textTop(c, surface.text, b.centerX - semibold.measureText(surface.text) / 2, top)
            top += labelH
        }
        if (more) windAndHumidity(c, b, w, top + gap, moreH)
    }

    /** The sky's icon and "9°C", centred, in the row from [top] [rowH] high. */
    private fun skyAndAir(c: Canvas, b: Box, w: HudWeather, top: Float, rowH: Float) {
        val h = b.height
        val icon = h * IconShare
        val gap = h * Gap
        val tempH = setText(bold, h * TempShare, Text)
        val tempW = bold.measureText(w.airText)
        setText(semibold, h * UnitShare, Dim)
        val unitW = semibold.measureText(CelsiusUnit)
        val sky = w.sky
        var x = b.centerX - ((if (sky != null) icon + gap else 0f) + tempW + unitW) / 2
        if (sky != null) {
            icon(c, skyIcon(sky), x, top + (rowH - icon) / 2, icon)
            x += icon + gap
        }
        val y = top + (rowH - tempH) / 2
        setText(bold, h * TempShare, Text)
        textTop(c, w.airText, x, y, bold)
        setText(semibold, h * UnitShare, Dim)
        textTop(c, CelsiusUnit, x + tempW, y + tempH * 0.18f)
    }

    /** The wind's arrow and speed, then the humidity, centred on the line from [top] [lineH] high. */
    private fun windAndHumidity(c: Canvas, b: Box, w: HudWeather, top: Float, lineH: Float) {
        val h = b.height
        val gap = h * Gap
        val space = h * 0.08f
        val wind = w.windText
        val humidity = w.humidityText
        val arrowDeg = w.windArrowDeg?.takeIf { wind != null }
        val arrow = if (arrowDeg != null) lineH * 0.8f else 0f
        setText(semibold, h * SmallShare, Text)
        val windW = wind?.let(semibold::measureText) ?: 0f
        val humW = humidity?.let(semibold::measureText) ?: 0f
        val between = if (wind != null && humidity != null) space else 0f
        var x = b.centerX - ((if (arrow > 0) arrow + gap else 0f) + windW + between + humW) / 2
        if (arrowDeg != null) {
            icon(c, listOf(windArrow(arrowDeg)), x, top + (lineH - arrow) / 2, arrow)
            x += arrow + gap
        }
        if (wind != null) {
            textTop(c, wind, x, top)
            x += windW + between
        }
        if (humidity != null) {
            setText(semibold, h * SmallShare, Dim)
            textTop(c, humidity, x, top)
        }
    }

    /** [shapes], drawn in a 24 by 24 box, scaled to [size] at [x], [y]. */
    private fun icon(c: Canvas, shapes: List<IconShape>, x: Float, y: Float, size: Float) {
        val k = size / IconBox
        for (shape in shapes) {
            when (shape) {
                is IconShape.Circle -> {
                    fill.color = shape.color
                    c.drawCircle(x + shape.cx * k, y + shape.cy * k, shape.r * k, fill)
                }
                is IconShape.Line -> {
                    line.color = shape.color
                    line.strokeWidth = max(1f, shape.width * k)
                    c.drawLine(x + shape.x1 * k, y + shape.y1 * k, x + shape.x2 * k, y + shape.y2 * k, line)
                }
                is IconShape.Polygon -> {
                    path.rewind()
                    shape.points.forEachIndexed { i, (px, py) ->
                        if (i == 0) path.moveTo(x + px * k, y + py * k) else path.lineTo(x + px * k, y + py * k)
                    }
                    path.close()
                    fill.color = shape.color
                    c.drawPath(path, fill)
                }
            }
        }
    }

    /** The paint's size, colour and letter spacing in pixels; returns its line height. */
    private fun setText(paint: Paint, size: Float, color: Int, spacingPx: Float = 0f): Float {
        paint.textSize = max(1f, size)
        paint.color = color
        paint.letterSpacing = if (spacingPx > 0) spacingPx / paint.textSize else 0f
        return paint.fontMetrics.let { it.descent - it.ascent }
    }

    private fun textTop(c: Canvas, text: String, x: Float, top: Float, paint: Paint = semibold) =
        c.drawText(text, x, top - paint.fontMetrics.ascent, paint)

    private companion object {
        const val IconBox = 24f
        const val IconShare = 0.26f
        const val TempShare = 0.3f
        const val UnitShare = 0.12f
        const val SmallShare = 0.095f
        const val Spacing = 0.012f
        const val Gap = 0.04f
        const val CelsiusUnit = "C"
        const val Text = 0xFFF4F4F4.toInt()
        const val Dim = 0xFFA3A6AD.toInt()
    }
}
