@file:Suppress("MagicNumber", "TooManyFunctions") // Overlays' Tech widgets' own sizes, copied as they are.

package com.flappedear.betterdriver.videoexport

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.flappedear.betterdriver.analysis.optimal.OptimalOverlayText
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.GForce
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.LapPhase
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.OverlayLap
import com.flappedear.betterdriver.timing.overlay.StackPosition
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import com.flappedear.betterdriver.timing.overlay.thin
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The data overlay (FBBD-152) in FlappedEar Overlays' "Tech HUD" layout (FBBD-347): every widget of
 * that template drawn as Overlays' Tech style draws it (FlappedEar/Overlay at 29308ee,
 * native/qml/widgets/tech): dark plates with two cut corners and an amber tab, Chakra Petch, the
 * same places, sizes and colours. Places come from [TechHudLayout]; a widget the session has no
 * value for is left out ([hudWidgets]).
 *
 * The lap tile shows Overlays' optional live delta (KAN-255) to the session's best lap. On a
 * point-to-point track (FBBD-155) the laps are runs, labelled "RUN".
 */
internal class TechHudPainter(fonts: HudFonts, private val data: OverlayData) {
    private val shown = hudWidgets(data)
    private val outline = thin(data.outline)

    private val semibold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.semibold
        fontFeatureSettings = TabularDigits
    }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.bold
        fontFeatureSettings = TabularDigits
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val oval = RectF()

    /** The scene scale of the frame being drawn ([TechHudLayout.scale]). */
    private var scale = 1f

    /** The layout of the last picture size, kept while the size stays. */
    private var last: TechHudLayout? = null

    fun layout(width: Int, height: Int): TechHudLayout = last?.takeIf { it.width == width && it.height == height }
        ?: TechHudLayout(width, height, shown).also { last = it }

    /**
     * Draws frame [f], the data at [sessionMicros], over the whole picture of [l]. With [optimal] (a
     * composite lap's video, FBBD-163) the lap tile shows the lap the part comes from over the
     * theoretical lap's running time, and its delta what it has gained on the fastest lap so far.
     */
    fun draw(
        canvas: Canvas,
        l: TechHudLayout,
        sessionMicros: Long,
        f: OverlayFrame,
        optimal: OptimalOverlayText? = null,
    ) {
        val s = l.scale
        scale = s
        for ((w, b) in l.boxes) {
            when (w) {
                HudWidget.Tachometer -> tachometer(canvas, b, f)
                HudWidget.Lap -> lap(canvas, b, s, f, optimal)
                HudWidget.Pedals -> pedals(canvas, b, s, f)
                HudWidget.Oil -> row(canvas, b, l.stack[w], Oil, f.car.oilC)
                HudWidget.Gearbox -> row(canvas, b, l.stack[w], Atf, f.car.gearboxC)
                HudWidget.Coolant -> row(canvas, b, l.stack[w], Coolant, f.car.coolantC)
                HudWidget.Tyres -> tyres(canvas, b, s, f)
                HudWidget.HeartRate -> heartRate(canvas, b, s, f)
                HudWidget.GRadar -> radar(canvas, b, sessionMicros, f)
                HudWidget.GReadout -> gReadout(canvas, b, s, f)
                HudWidget.Steering -> steering(canvas, b, s, f)
                HudWidget.TrackMap -> map(canvas, b, s, sessionMicros, f)
            }
        }
    }

    // TechPanel.qml: a dark plate with the top right and bottom left corners cut, an amber tab on top.
    private fun panel(c: Canvas, b: Box, s: Float, stack: StackPosition = StackPosition.Single) {
        val cutTop = stack == StackPosition.Single || stack == StackPosition.Top
        val cutBottom = stack == StackPosition.Single || stack == StackPosition.Bottom
        val cut = min(18 * s, min(b.width / 3, b.height / 2))
        path.rewind()
        path.moveTo(b.left, b.top)
        path.lineTo(if (cutTop) b.right - cut else b.right, b.top)
        path.lineTo(b.right, if (cutTop) b.top + cut else b.top)
        path.lineTo(b.right, b.bottom)
        path.lineTo(if (cutBottom) b.left + cut else b.left, b.bottom)
        path.lineTo(b.left, if (cutBottom) b.bottom - cut else b.bottom)
        path.close()
        fill.color = Plate
        c.drawPath(path, fill)
        if (cutTop) {
            fill.color = Amber
            c.drawRect(b.left, b.top, b.left + min(b.width * 0.3f, 46 * s), b.top + max(2f, 3.5f * s), fill)
        }
    }

    private fun pad(s: Float) = 14 * s

    // TechTachometer.qml: a 270° arc with a red zone, the scale in thousands, speed in the middle.
    private fun tachometer(c: Canvas, b: Box, f: OverlayFrame) {
        val side = min(b.width, b.height)
        val cx = b.centerX
        val cy = b.centerY
        val r = side * 0.43f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val redFrom = rpmRatio(RpmWarning)
        line.pathEffect = null
        line.strokeWidth = max(2f, side * 0.02f)
        line.strokeCap = Paint.Cap.ROUND
        line.color = withAlpha(White, 0.16f)
        c.drawArc(oval, ArcStart, ArcSweep, false, line)
        line.strokeCap = Paint.Cap.BUTT
        line.color = withAlpha(Red, 0.5f)
        c.drawArc(oval, ArcStart + ArcSweep * redFrom, ArcSweep * (1 - redFrom), false, line)

        val steps = (RpmMax / 1000).roundToInt().coerceIn(2, 16)
        semibold.textSize = max(8f, side * 0.052f)
        semibold.letterSpacing = 0f
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val a = Math.toRadians((ArcStart + ArcSweep * t).toDouble())
            line.color = if (t >= redFrom) Red else withAlpha(White, 0.6f)
            line.strokeWidth = max(1f, side * 0.009f)
            c.drawLine(
                cx + (cos(a) * r * 0.84).toFloat(), cy + (sin(a) * r * 0.84).toFloat(),
                cx + (cos(a) * r * 0.9).toFloat(), cy + (sin(a) * r * 0.9).toFloat(), line,
            )
            semibold.color = withAlpha(White, 0.7f)
            val label = ((RpmMax * t) / 1000).roundToInt().toString()
            centred(c, label, cx + (cos(a) * r * 0.72).toFloat(), cy + (sin(a) * r * 0.72).toFloat(), semibold)
        }
        val rpm = f.car.rpm
        if (rpm != null) {
            line.strokeCap = Paint.Cap.ROUND
            line.strokeWidth = max(2f, side * 0.02f)
            line.color = if (rpm >= RpmWarning) Red else Text
            c.drawArc(oval, ArcStart, ArcSweep * max(0.001f, rpmRatio(rpm)), false, line)
        }

        val speed = f.speedKmh?.roundToInt()?.toString() ?: Dash
        val rpmText = (rpm?.roundToInt()?.toString() ?: Dash) + " RPM"
        val gap = side * 0.012f
        val h1 = setText(bold, side * 0.25f, Text)
        val h2 = setText(semibold, side * 0.055f, Dim, side * 0.008f)
        val total = h1 + h2 + lineHeight(semibold.also { it.textSize = side * 0.065f }) + 2 * gap
        var top = cy + side * 0.04f - total / 2
        textTop(c, speed, cx - bold.measureText(speed) / 2, top, bold)
        top += h1 + gap
        setText(semibold, side * 0.055f, Dim, side * 0.008f)
        textTop(c, "KM/H", cx - semibold.measureText("KM/H") / 2, top, semibold)
        top += h2 + gap
        setText(semibold, side * 0.065f, Dim)
        textTop(c, rpmText, cx - semibold.measureText(rpmText) / 2, top, semibold)
    }

    private fun rpmRatio(v: Double): Float = (v / RpmMax).coerceIn(0.0, 1.0).toFloat()

    // TechLapTime.qml: "LAP" and the lap number in amber, the running time, the best lap, the delta.
    private fun lap(c: Canvas, b: Box, s: Float, f: OverlayFrame, optimal: OptimalOverlayText?) {
        panel(c, b, s)
        val p = pad(s)
        val h = b.height
        val lap = f.lap
        setText(semibold, h * 0.1f, Dim, h * 0.012f)
        val caption = lapCaption(optimal, b.width - 2 * p)
        textTop(c, caption.uppercase(Locale.ROOT), b.left + p, b.top + p * 1.2f, semibold)
        if (optimal == null) {
            val number = lap?.number?.toString() ?: Dash
            setText(bold, h * 0.15f, Amber)
            textTop(c, number, b.right - p * 1.4f - bold.measureText(number), b.top + p, bold)
        }

        val time = when {
            optimal != null -> lapTime(optimal.theoreticalMicros)
            lap != null -> lapTime(lap.lapMicros)
            else -> NoTime
        }
        setText(bold, min(h * 0.3f, (b.width - 2 * p) / 4.6f), timeColor(lap, optimal))
        textMiddle(c, time, b.left + p, b.centerY + h * 0.03f, bold)

        val bottom = b.bottom - p * 0.9f
        setText(semibold, h * 0.1f, Dim)
        val best = data.bestLapMicros?.let(::lapTime) ?: Dash
        val x = textBottom(c, "BEST ", b.left + p, bottom, semibold)
        semibold.color = Text
        textBottom(c, best, x, bottom, semibold)

        val delta = if (optimal != null) optimal.deltaSeconds else lap?.deltaMicros?.let { it / MicrosPerSecond }
        if (delta != null) delta(c, delta, b.right - p * 1.4f, bottom, h)
    }

    /** A single-lap export (FBBD-346) shows 0:00.000 before the line and holds the final time in green after it. */
    private fun timeColor(lap: OverlayLap?, optimal: OptimalOverlayText?): Int =
        if (optimal == null && lap?.phase == LapPhase.Finished) Green else Text

    /** "LAP" or "RUN"; for a composite lap the part's caption, as much of it as fits [width]. */
    private fun lapCaption(optimal: OptimalOverlayText?, width: Float): String {
        if (optimal == null) return if (data.pointToPoint) "RUN" else "LAP"
        val captions = optimal.captions
        return captions.firstOrNull { semibold.measureText(it) <= width } ?: captions.lastOrNull().orEmpty()
    }

    /** The delta to the best lap, ending at [right]: green ahead, orange behind. */
    private fun delta(c: Canvas, seconds: Double, right: Float, bottom: Float, h: Float) {
        val text = lapDelta(seconds)
        setText(bold, h * 0.13f, if (seconds <= 0) Green else Orange)
        textBottom(c, text, right - bold.measureText(text), bottom, bold)
    }

    // TechPedals.qml: throttle and brake, each a label, its % and a slanted bar.
    private fun pedals(c: Canvas, b: Box, s: Float, f: OverlayFrame) {
        panel(c, b, s)
        val p = pad(s)
        val left = b.left + p
        val right = b.right - p
        val top = b.top + p * 1.2f
        val height = b.bottom - p - top
        val rowH = height * 0.42f
        val spacing = height - 2 * rowH
        listOf(Triple("THROTTLE", f.car.throttlePct, Green), Triple("BRAKE", f.car.brakePct, Red))
            .forEachIndexed { i, (name, value, tint) ->
                val rowTop = top + i * (rowH + spacing)
                setText(semibold, rowH * 0.3f, Dim, rowH * 0.03f)
                textTop(c, name, left, rowTop, semibold)
                val v = value?.let { "${it.roundToInt()}%" } ?: Dash
                setText(bold, rowH * 0.34f, Text)
                textTop(c, v, right - bold.measureText(v), rowTop, bold)
                val barH = rowH * 0.4f
                val barBottom = rowTop + rowH
                val barLeft = left + barH * 0.33f
                fill.color = withAlpha(White, 0.1f)
                slanted(c, barLeft, right, barBottom - barH, barH)
                val fraction = ((value ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat()
                if (fraction > 0) {
                    fill.color = tint
                    slanted(c, barLeft, barLeft + (right - barLeft) * fraction, barBottom - barH, barH)
                }
            }
    }

    /** A bar from [x0] to [x1] whose bottom edge sits a third of its height further left. */
    private fun slanted(c: Canvas, x0: Float, x1: Float, top: Float, h: Float) {
        val shift = h * 0.33f
        path.rewind()
        path.moveTo(x0, top)
        path.lineTo(x1, top)
        path.lineTo(x1 - shift, top + h)
        path.lineTo(x0 - shift, top + h)
        path.close()
        c.drawPath(path, fill)
    }

    // TechCustomValue.qml: label, range bar with the normal band, value turning red at the warning.
    private fun row(c: Canvas, b: Box, stack: StackPosition?, spec: RowSpec, value: Double?) {
        val s = scale
        val position = stack ?: StackPosition.Single
        panel(c, b, s, position)
        val p = pad(s)
        val h = b.height
        val inner = b.width - 2 * p
        if (position == StackPosition.Middle || position == StackPosition.Bottom) {
            fill.color = withAlpha(White, 0.08f)
            c.drawRect(b.left + p, b.top, b.right - p, b.top + max(1f, s), fill)
        }
        val hot = value != null && value >= spec.warning
        val spacing = h * 0.3f
        setText(semibold, h * 0.28f, if (hot) Red else Dim, h * 0.02f)
        val labelW = max(inner * 0.24f, min(semibold.measureText(spec.label), inner * 0.36f))
        textMiddle(c, spec.label, b.left + p, b.centerY, semibold)

        val number = value?.roundToInt()?.toString() ?: Dash
        val numberSize = h * 0.36f
        setText(bold, numberSize, if (hot) Red else if (value != null) Text else Dim)
        val numberW = bold.measureText(number)
        val unitPaint = semibold
        setText(unitPaint, numberSize * UnitShare, Dim)
        val unitText = " °C"
        val unitW = unitPaint.measureText(unitText)
        val valueW = max(numberW + unitW, h * 1.5f)
        val valueRight = b.right - p
        val baseline = b.centerY - (bold.fontMetrics.ascent + bold.fontMetrics.descent) / 2
        c.drawText(unitText, valueRight - unitW, baseline, unitPaint)
        c.drawText(number, valueRight - unitW - numberW, baseline, bold)

        val barLeft = b.left + p + labelW + spacing
        val barRight = valueRight - valueW - spacing
        if (barRight > barLeft) {
            val barH = max(2f, h * 0.13f)
            val barTop = b.centerY - barH / 2
            val w = barRight - barLeft
            fun at(v: Double) = ((v - spec.min) / (spec.max - spec.min)).coerceIn(0.0, 1.0).toFloat()
            fill.color = withAlpha(White, 0.13f)
            c.drawRect(barLeft, barTop, barRight, barTop + barH, fill)
            fill.color = withAlpha(Green, 0.35f)
            c.drawRect(barLeft + w * at(spec.normalLow), barTop, barLeft + w * at(spec.normalHigh), barTop + barH, fill)
            if (value != null) {
                val markW = max(2f, h * 0.08f)
                val x = barLeft + w * at(value)
                fill.color = if (hot) Red else Text
                c.drawRect(x - markW / 2, b.centerY - barH * 1.2f, x + markW / 2, b.centerY + barH * 1.2f, fill)
            }
        }
    }

    // TechTyres.qml: a block per tyre coloured by temperature, its temperature and pressure beside it.
    private fun tyres(c: Canvas, b: Box, s: Float, f: OverlayFrame) {
        panel(c, b, s)
        val p = pad(s)
        val h = b.height
        setText(semibold, h * 0.09f, Dim, h * 0.01f)
        val titleTop = b.top + p * 1.2f
        textTop(c, "TYRES · °C / BAR", b.left + p, titleTop, semibold)
        val gridTop = titleTop + lineHeight(semibold) + p * 0.6f
        val gridW = b.width - 2 * p
        val gridH = b.bottom - p - gridTop
        val colGap = gridW * 0.1f
        val rowGap = gridH * 0.08f
        val cellW = (gridW - colGap) / 2
        val cellH = (gridH - rowGap) / 2
        for (i in 0 until 4) {
            val right = i % 2 == 1
            val x0 = b.left + p + (i % 2) * (cellW + colGap)
            val y0 = gridTop + (i / 2) * (cellH + rowGap)
            val temp = f.car.tyreTemps[i]
            val pressure = f.car.tyrePressures[i]
            val blockW = cellH * 0.42f
            val blockH = cellH * 0.92f
            val blockX = if (right) x0 + cellW - blockW else x0
            val blockY = y0 + (cellH - blockH) / 2
            fill.color = tyreColor(temp)
            c.drawRoundRect(blockX, blockY, blockX + blockW, blockY + blockH, blockW * 0.2f, blockW * 0.2f, fill)
            val tText = temp?.roundToInt()?.toString() ?: Dash
            val pText = pressure?.let { String.format(Locale.ROOT, "%.2f", it) } ?: Dash
            val tH = setText(bold, cellH * 0.44f, Text)
            val pH = lineHeight(semibold.also { it.textSize = cellH * 0.32f })
            val top = y0 + (cellH - tH - pH) / 2
            val gap = cellH * 0.18f
            val textX = { t: String, paint: Paint ->
                if (right) blockX - gap - paint.measureText(t) else blockX + blockW + gap
            }
            textTop(c, tText, textX(tText, bold), top, bold)
            setText(semibold, cellH * 0.32f, Dim)
            textTop(c, pText, textX(pText, semibold), top + tH, semibold)
        }
    }

    private fun tyreColor(t: Double?): Int = when {
        t == null -> 0xFF3A3D44.toInt()
        t < TyreCold -> 0xFF3D8BFF.toInt()
        t > TyreHot -> Red
        else -> blend(Green, Amber, ((t - TyreCold) / (TyreHot - TyreCold)).toFloat())
    }

    // TechHeartRate.qml: a red heart and "BPM" over the number.
    private fun heartRate(c: Canvas, b: Box, s: Float, f: OverlayFrame) {
        panel(c, b, s)
        val h = b.height
        val icon = h * 0.14f
        val gap = h * 0.05f
        val labelH = setText(semibold, h * 0.1f, Dim, h * 0.012f)
        val rowH = max(icon, labelH)
        val valueH = lineHeight(bold.also { it.textSize = h * 0.36f })
        val top = b.centerY - (rowH + h * 0.04f + valueH) / 2
        val rowW = icon + gap + semibold.measureText("BPM")
        val x = b.centerX - rowW / 2
        heart(c, x, top + (rowH - icon) / 2, icon)
        textTop(c, "BPM", x + icon + gap, top + (rowH - labelH) / 2, semibold)
        val v = f.car.heartRate?.roundToInt()?.toString() ?: Dash
        setText(bold, h * 0.36f, Text)
        textTop(c, v, b.centerX - bold.measureText(v) / 2, top + rowH + h * 0.04f, bold)
    }

    private fun heart(c: Canvas, x: Float, y: Float, size: Float) {
        val k = size / 24
        fun px(v: Float) = x + v * k
        fun py(v: Float) = y + v * k
        path.rewind()
        path.moveTo(px(12f), py(21f))
        path.cubicTo(px(12f), py(21f), px(4.5f), py(16.4f), px(2.4f), py(11.7f))
        path.cubicTo(px(0.9f), py(8.2f), px(3.1f), py(4.5f), px(6.7f), py(4.5f))
        path.cubicTo(px(8.9f), py(4.5f), px(10.3f), py(5.7f), px(12f), py(7.6f))
        path.cubicTo(px(13.7f), py(5.7f), px(15.1f), py(4.5f), px(17.3f), py(4.5f))
        path.cubicTo(px(20.9f), py(4.5f), px(23.1f), py(8.2f), px(21.6f), py(11.7f))
        path.cubicTo(px(19.5f), py(16.4f), px(12f), py(21f), px(12f), py(21f))
        path.close()
        fill.color = Red
        c.drawPath(path, fill)
    }

    // TechRadar.qml: rings every 0.5 g to 1.5 g, a crosshair, an amber dot and its last second.
    private fun radar(c: Canvas, b: Box, sessionMicros: Long, f: OverlayFrame) {
        val side = min(b.width, b.height)
        val m = side / 2
        val cx = b.centerX
        val cy = b.centerY
        val field = m * 0.92f
        val scale = field / MaxG
        fill.color = withAlpha(0xFF08090B.toInt(), 0.93f)
        c.drawCircle(cx, cy, m, fill)
        line.pathEffect = null
        line.color = withAlpha(White, 0.16f)
        line.strokeWidth = max(1f, side * 0.006f)
        var g = RingStep
        while (g <= MaxG + 1e-6f) {
            c.drawCircle(cx, cy, g * scale, line)
            g += RingStep
        }
        c.drawLine(cx, cy - field, cx, cy + field, line)
        c.drawLine(cx - field, cy, cx + field, cy, line)
        semibold.textSize = max(7f, side * 0.055f)
        semibold.letterSpacing = 0f
        semibold.color = withAlpha(White, 0.5f)
        c.drawText("1.0g", cx + scale * 0.72f, cy - scale * 0.72f, semibold)

        for (i in TrailPoints downTo 1) {
            val at = data.gAt(sessionMicros - TrailMicros * i / TrailPoints) ?: continue
            val fade = 1 - i.toFloat() / (TrailPoints + 1)
            val (x, y) = place(at, cx, cy, scale)
            fill.color = withAlpha(Amber, fade * 0.5f)
            c.drawCircle(x, y, side * (0.01f + 0.02f * fade), fill)
        }
        val now = f.g ?: return
        val (x, y) = place(now, cx, cy, scale)
        fill.color = Amber
        c.drawCircle(x, y, side * 0.04f, fill)
        line.color = 0xFF1A1200.toInt()
        line.strokeWidth = max(1f, side * 0.01f)
        c.drawCircle(x, y, side * 0.04f, line)
    }

    /**
     * Where the radar's dot goes, as Overlays puts it: braking up, speeding up down, and a turn to the
     * left (acceleration to the left) to the right, the way the body is pushed.
     */
    private fun place(g: GForce, cx: Float, cy: Float, scale: Float): Pair<Float, Float> {
        val left = -g.lateral
        val forward = g.longitudinal
        val total = hypot(left, forward)
        val k = if (total > MaxG) MaxG / total else 1.0
        return (cx + left * k * scale).toFloat() to (cy + forward * k * scale).toFloat()
    }

    // TechGForce.qml: "G", the combined G, then lateral and longitudinal.
    private fun gReadout(c: Canvas, b: Box, s: Float, f: OverlayFrame) {
        panel(c, b, s)
        val p = pad(s)
        val h = b.height
        val g = f.g
        setText(semibold, h * 0.16f, Dim, h * 0.015f)
        textTop(c, "G", b.left + p, b.top + h * 0.2f, semibold)
        val combined = g?.let { two(hypot(it.lateral, it.longitudinal)) } ?: Dash
        setText(bold, h * 0.36f, Text)
        textTop(c, combined, b.right - p - bold.measureText(combined), b.top + h * 0.1f, bold)
        val bottom = b.bottom - h * 0.14f
        setText(semibold, h * 0.15f, Dim)
        var x = textBottom(c, "LAT ", b.left + p, bottom, semibold)
        semibold.color = Text
        x = textBottom(c, g?.let { two(abs(it.lateral)) } ?: Dash, x, bottom, semibold)
        semibold.color = Dim
        x = textBottom(c, "LON ", x + h * 0.2f, bottom, semibold)
        semibold.color = Text
        textBottom(c, g?.let { two(abs(it.longitudinal)) } ?: Dash, x, bottom, semibold)
    }

    // TechSteeringWheel.qml: "STEER" and the angle ("90° L") over a wheel turned by it.
    private fun steering(c: Canvas, b: Box, s: Float, f: OverlayFrame) {
        panel(c, b, s)
        val p = pad(s)
        val left = b.left + p
        val right = b.right - p
        val top = b.top + p * 1.2f
        val bottom = b.bottom - p
        val rowH = (bottom - top) * 0.42f
        setText(semibold, rowH * 0.3f, Dim, rowH * 0.03f)
        textTop(c, "STEER", left, top, semibold)
        val angle = f.car.steeringDeg
        val text = steeringText(angle)
        setText(bold, rowH * 0.34f, Text)
        textTop(c, text, right - bold.measureText(text), top, bold)

        val stageTop = top + rowH * 0.5f
        val size = max(0f, min(right - left, bottom - stageTop))
        val cx = (left + right) / 2
        val cy = (stageTop + bottom) / 2
        c.save()
        c.rotate(-(angle ?: 0.0).toFloat(), cx, cy)
        val half = size / 2
        val rim = max(2f, size * 0.07f)
        line.pathEffect = null
        line.color = Text
        line.strokeWidth = rim
        c.drawCircle(cx, cy, half - rim / 2, line)
        fill.color = Text
        val spoke = size * 0.07f
        c.drawRect(cx - size * 0.45f, cy - spoke / 2, cx + size * 0.45f, cy + spoke / 2, fill)
        c.drawRect(cx - spoke / 2, cy, cx + spoke / 2, cy + size * 0.4f, fill)
        c.drawCircle(cx, cy, size * 0.1f, fill)
        fill.color = Amber
        c.drawRect(cx - size * 0.045f, cy - half, cx + size * 0.045f, cy - half + size * 0.16f, fill)
        c.restore()
    }

    // TechTrackMap.qml + TrackMapCore.qml: "TRACK" and the lap, the whole track, the lap's trail, the car.
    private fun map(c: Canvas, b: Box, s: Float, sessionMicros: Long, f: OverlayFrame) {
        panel(c, b, s)
        val p = pad(s)
        val h = b.height
        val cut = min(18 * s, min(b.width / 3, h / 2))
        setText(semibold, h * 0.055f, Dim, h * 0.006f)
        val titleTop = b.top + p * 1.2f
        textTop(c, "TRACK", b.left + p, titleTop, semibold)
        val lap = f.lap
        if (lap != null) {
            val text = "${if (data.pointToPoint) "RUN" else "LAP"} ${lap.number}"
            setText(bold, h * 0.065f, Text)
            textTop(c, text, b.right - p - cut * 0.6f - bold.measureText(text), titleTop, bold)
        }
        setText(semibold, h * 0.055f, Dim)
        val area = Box(b.left + p, titleTop + lineHeight(semibold) + p * 0.4f, b.right - p, b.bottom - p)
        val view = MapView.fit(outline, area) ?: return
        val side = max(1f, min(area.width, area.height))
        val lw = max(2f, side * 0.014f)
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        line.pathEffect = null
        polyline(outline, view)
        line.strokeWidth = lw + 3
        line.color = withAlpha(0xFF000000.toInt(), 0.5f)
        c.drawPath(path, line)
        line.strokeWidth = lw
        line.color = MapLine
        c.drawPath(path, line)

        startLine(c, view, side)
        val trail = data.trail(sessionMicros)
        if (trail.size > 1) {
            polyline(trail, view)
            line.strokeWidth = lw
            line.color = Amber
            c.drawPath(path, line)
        }
        f.position?.let { car(c, view.px(it), side) }
    }

    /** The start/finish line: a short dashed tick across the track. */
    private fun startLine(c: Canvas, view: MapView, side: Float) {
        val (a, z) = data.startLine ?: return
        val pa = view.px(a)
        val pz = view.px(z)
        val length = hypot(pz.first - pa.first, pz.second - pa.second)
        if (length <= 0) return
        val reach = side * 0.03f
        val mx = (pa.first + pz.first) / 2
        val my = (pa.second + pz.second) / 2
        val ux = (pz.first - pa.first) / length * reach
        val uy = (pz.second - pa.second) / length * reach
        line.pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        line.strokeWidth = max(2f, side * 0.01f)
        line.strokeCap = Paint.Cap.BUTT
        line.color = Text
        c.drawLine(mx - ux, my - uy, mx + ux, my + uy, line)
        line.pathEffect = null
        line.strokeCap = Paint.Cap.ROUND
    }

    /** The car: an amber dot with a dark rim in a faint halo. */
    private fun car(c: Canvas, at: Pair<Float, Float>, side: Float) {
        val (x, y) = at
        val dot = max(4f, side * 0.03f)
        fill.color = withAlpha(Amber, 0.25f)
        c.drawCircle(x, y, dot * 1.6f, fill)
        fill.color = Amber
        c.drawCircle(x, y, dot, fill)
        line.color = Plate or 0xFF000000.toInt()
        line.strokeWidth = max(1.5f, dot * 0.3f)
        c.drawCircle(x, y, dot, line)
    }

    /** [points] into [path], broken at each null. */
    private fun polyline(points: List<Xy?>, view: MapView) {
        path.rewind()
        var drawing = false
        for (p in points) {
            if (p == null) {
                drawing = false
                continue
            }
            val (x, y) = view.px(p)
            if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
            drawing = true
        }
    }

    /** The paint's size, colour and letter spacing in pixels; returns its line height. */
    private fun setText(paint: Paint, size: Float, color: Int, spacingPx: Float = 0f): Float {
        paint.textSize = max(1f, size)
        paint.color = color
        paint.letterSpacing = if (spacingPx > 0) spacingPx / paint.textSize else 0f
        return lineHeight(paint)
    }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    /** Text whose line box starts at [top], as a QML Label placed there. */
    private fun textTop(c: Canvas, text: String, x: Float, top: Float, paint: Paint) =
        c.drawText(text, x, top - paint.fontMetrics.ascent, paint)

    /** Text whose line box is centred on [centerY]. */
    private fun textMiddle(c: Canvas, text: String, x: Float, centerY: Float, paint: Paint) =
        c.drawText(text, x, centerY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)

    /** Text whose line box ends at [bottom]; returns where the next run starts. */
    private fun textBottom(c: Canvas, text: String, x: Float, bottom: Float, paint: Paint): Float {
        c.drawText(text, x, bottom - paint.fontMetrics.descent, paint)
        return x + paint.measureText(text)
    }

    private fun centred(c: Canvas, text: String, cx: Float, cy: Float, paint: Paint) =
        textMiddle(c, text, cx - paint.measureText(text) / 2, cy, paint)

    /** The OIL, ATF and COOLANT rows' ranges in the Tech HUD template, in °C. */
    private class RowSpec(
        val label: String,
        val min: Double,
        val max: Double,
        val normalLow: Double,
        val normalHigh: Double,
        val warning: Double,
    )

    private companion object {
        const val TabularDigits = "'tnum' 1"
        const val Dash = "—"
        const val NoTime = "—:—.———"
        const val MicrosPerSecond = 1_000_000.0
        const val ArcStart = 135f
        const val ArcSweep = 270f
        const val RpmMax = 9000.0
        const val RpmWarning = 7500.0
        const val MaxG = 1.5f
        const val RingStep = 0.5f
        const val TrailPoints = 14
        const val TrailMicros = 1_000_000L
        const val TyreCold = 60.0
        const val TyreHot = 100.0

        /** The unit after a row's value, in Qt's "size 2" against the value's size. */
        const val UnitShare = 0.55f

        const val White = 0xFFFFFFFF.toInt()
        const val Text = 0xFFF4F4F4.toInt()
        const val Dim = 0xFFA3A6AD.toInt()
        const val Amber = 0xFFFCB203.toInt()
        const val Green = 0xFF4FD17A.toInt()
        const val Red = 0xFFFF4D3D.toInt()
        const val Orange = 0xFFFF9F1A.toInt()
        const val MapLine = 0xFF4A545C.toInt()

        /** #0c0e11 at 86 %. */
        const val Plate = 0xDB0C0E11.toInt()

        val Oil = RowSpec("OIL", 60.0, 150.0, 90.0, 125.0, 128.0)
        val Atf = RowSpec("ATF", 40.0, 140.0, 70.0, 110.0, 118.0)
        val Coolant = RowSpec("COOLANT", 60.0, 120.0, 85.0, 104.0, 105.0)
    }
}

/** The whole track fitted into a box, north up, at 94 % as Overlays fits it. */
private class MapView(
    private val cx: Float,
    private val cy: Float,
    private val midX: Double,
    private val midY: Double,
    private val k: Double,
) {
    fun px(p: Xy): Pair<Float, Float> = (cx + (p.x - midX) * k).toFloat() to (cy - (p.y - midY) * k).toFloat()

    companion object {
        fun fit(outline: List<Xy>, area: Box): MapView? {
            if (outline.size < 2 || area.width <= 0 || area.height <= 0) return null
            val minX = outline.minOf { it.x }
            val maxX = outline.maxOf { it.x }
            val minY = outline.minOf { it.y }
            val maxY = outline.maxOf { it.y }
            val k = min(area.width / max(1.0, maxX - minX), area.height / max(1.0, maxY - minY)) * 0.94
            return MapView(area.centerX, area.centerY, (minX + maxX) / 2, (minY + maxY) / 2, k)
        }
    }
}

/** "1:23.456": a lap time to the thousandth, as Overlays' formatLapTime with 3 decimals. */
internal fun lapTime(micros: Long): String {
    val millis = (micros.coerceAtLeast(0) / 1000.0).roundToLong()
    return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, millis % 1000)
}

/** "+0.123", "-0.456": a delta in seconds as Overlays' formatLapDelta with 3 decimals. */
internal fun lapDelta(seconds: Double): String =
    (if (seconds > 0) "+" else "") + String.format(Locale.ROOT, "%.3f", seconds)

/** "90° L", "45° R", "0°": the wheel angle as Overlays writes it; never wrapped past a turn. */
internal fun steeringText(degrees: Double?): String {
    if (degrees == null) return "—"
    val whole = abs(degrees).roundToInt()
    return if (whole == 0) "0°" else "$whole° ${if (degrees > 0) "L" else "R"}"
}

private fun two(v: Double) = String.format(Locale.ROOT, "%.2f", v)

/** [color] with its alpha set to [alpha], 0 to 1. */
private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).roundToInt() shl 24)

/** [a] tinted towards [b] by [t], 0 to 1, as Qt.tint with [b] at alpha [t]. */
private fun blend(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).roundToInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Chakra Petch, the Tech style's font (Overlays' TechLabel.qml), as Android typefaces. */
internal class HudFonts(val semibold: Typeface, val bold: Typeface) {
    companion object {
        /** Reads the fonts; falls back to the system's if a font cannot be read. */
        fun load(context: Context) = HudFonts(
            font(context, com.flappedear.betterdriver.core.ui.R.font.chakra_petch_semibold, Typeface.DEFAULT),
            font(context, com.flappedear.betterdriver.core.ui.R.font.chakra_petch_bold, Typeface.DEFAULT_BOLD),
        )

        private fun font(context: Context, id: Int, fallback: Typeface): Typeface = try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (ignored: Resources.NotFoundException) {
            fallback
        }
    }
}
