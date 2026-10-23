package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import kotlin.math.hypot

/** The car's acceleration in g: [forward] positive speeding up, negative braking; [left] positive in a left turn. */
@Immutable
data class RadarPoint(val forward: Float, val left: Float)

/** The G radar's dot ([now]) and its trail of the half second before it, oldest first (FBBD-318). */
@Immutable
data class GRadar(val now: RadarPoint?, val trail: List<RadarPoint>)

/**
 * The G radar on the second screen (UI-240 A): the 0.5 g and 1 g rings only (UI-241 B), marked as in
 * the picked mockup, no readouts such as grip used or G (UI-242 C), and the dot moved the way the driver is pushed (UI-243 A): braking moves it up, a
 * right turn moves it left. The trail is the half second before the dot, drawn smaller and fainter
 * the older it is. Drawn at the screen's 4 Hz with nothing animated in between. Beyond [EdgeG] the
 * dot stays on the edge.
 */
@Composable
fun GRadarView(radar: GRadar?, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    BoxWithConstraints(modifier.background(c.surface, RoundedCornerShape(8.dp)).padding(RadarPadding)) {
        val side = minOf(maxWidth, maxHeight)
        // One g from the centre, as the canvas draws it.
        val ring = side / 2 / EdgeG
        val centre = maxWidth / 2
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) { drawRadar(radar, c.line, c.textDim, c.text) }
            TrackLabel("Brake", Modifier.align(Alignment.TopCenter))
            TrackLabel("Gas", Modifier.align(Alignment.BottomCenter))
            // Each ring's label starts just right of where it crosses the axis, above the axis.
            val axis = Modifier.align(Alignment.CenterStart)
            TrackLabel("0.5", axis.offset(x = centre + ring * HalfG + LabelGap, y = -LabelRise))
            TrackLabel("1 g", axis.offset(x = centre + ring + LabelGap, y = -LabelRise))
        }
    }
}

private fun DrawScope.drawRadar(radar: GRadar?, line: Color, dim: Color, dot: Color) {
    val centre = Offset(size.width / 2, size.height / 2)
    // One g, so that [EdgeG] reaches the edge of the square.
    val g = minOf(size.width, size.height) / 2 / EdgeG
    val stroke = Stroke(RingWidth.toPx())
    drawCircle(line, g, centre, style = stroke)
    drawCircle(line, g * HalfG, centre, style = stroke)
    val reach = g * AxisG
    drawLine(line, Offset(centre.x - reach, centre.y), Offset(centre.x + reach, centre.y), RingWidth.toPx())
    drawLine(line, Offset(centre.x, centre.y - reach), Offset(centre.x, centre.y + reach), RingWidth.toPx())
    // The way the driver is pushed: braking (forward < 0) up, a right turn (left < 0) left.
    fun at(p: RadarPoint): Offset {
        val r = hypot(p.forward, p.left)
        val k = if (r > EdgeG) EdgeG / r else 1f
        return Offset(centre.x + p.left * k * g, centre.y + p.forward * k * g)
    }
    val trail = radar?.trail.orEmpty()
    trail.forEachIndexed { i, p ->
        val age = (i + 1).toFloat() / (trail.size + 1)
        val radius = TrailMinRadius + (DotRadius - TrailMinRadius) * age * TrailShare
        drawCircle(dim, radius.toPx(), at(p), alpha = TrailMinAlpha + (1 - TrailMinAlpha) * age)
    }
    radar?.now?.let { drawCircle(dot, DotRadius.toPx(), at(it)) }
}

/** The radar reaches this many g at its edge: Arek's laps reach about 1.0 g braking and cornering. */
private const val EdgeG = 1.2f
private const val HalfG = 0.5f

/** The axes stop short of the edge, so the Brake and Gas labels sit clear of them. */
private const val AxisG = 1.1f

/** The oldest trail point's opacity; the newest nears the dot's. */
private const val TrailMinAlpha = 0.3f

/** The newest trail point's size as a part of the dot's. */
private const val TrailShare = 0.6f

private val RadarPadding = 8.dp
private val RingWidth = 1.5.dp
private val DotRadius = 9.dp
private val TrailMinRadius = 2.dp
private val LabelGap = 3.dp
private val LabelRise = 9.dp

/**
 * Example radar: braking into a right-hand corner, from Arek's Jastrząb 29 Aug 16:06 session
 * (RaceChrono's G, 0.1 s apart).
 */
@Suppress("MagicNumber") // Example data.
val ExampleRadar = GRadar(
    now = RadarPoint(-0.63f, -0.53f),
    trail = listOf(
        RadarPoint(-0.58f, -0.19f),
        RadarPoint(-0.63f, -0.28f),
        RadarPoint(-0.64f, -0.35f),
        RadarPoint(-0.65f, -0.43f),
    ),
)

@Preview(name = "Track screen, second screen with the G radar", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenRadarPreview() {
    BetterDriverTheme(track = true) {
        TrackScreenContent(ExampleTrackState.copy(radarOn = true, radar = ExampleRadar), page = 1)
    }
}
