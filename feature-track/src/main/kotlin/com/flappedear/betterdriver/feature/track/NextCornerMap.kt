package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.CoachLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A point of the next corner's map, in metres east and north of any fixed origin. */
@Immutable
data class MapPoint(val east: Float, val north: Float)

/** What the driver does along the target line (UI-172 C): brake red, coast grey, throttle green. */
enum class MapPedal { Brake, Coast, Throttle }

/**
 * The next corner on the map screen (UI-171 B, UI-172 C): the road the laps used there ([left] and
 * [right] edge), the line plan's target through it ([line], each point with its [pedals], or null
 * where not known) and where the car is ([car], null when it is not near this corner). [label] is
 * the corner as the coach says it: "T1".
 */
@Immutable
data class NextCorner(
    val label: String,
    val left: List<MapPoint>,
    val right: List<MapPoint>,
    val line: List<MapPoint>,
    val pedals: List<MapPedal?>,
    val car: MapPoint?,
)

/**
 * Draws [corner] north up, scaled to fit with [MarginMeters] around the road, on the screen's
 * surface. Without a corner (no line plan near) the surface stays empty.
 */
@Composable
fun NextCornerMap(corner: NextCorner?, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Box(modifier.background(c.surface, RoundedCornerShape(8.dp))) {
        if (corner != null) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) { drawCorner(corner, c.loss, c.textDim, c.gain) }
            Text(
                corner.label,
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                style = TextStyle(color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * The map screen (UI-171 B): the status strip and the coach line stay, the next corner takes the
 * rest, drawn as the line plan's target coloured by the pedals (UI-172 C).
 */
@Composable
internal fun ColumnScope.MapScreen(state: TrackScreenState, coach: Modifier) {
    NextCornerMap(state.nextCorner, Modifier.fillMaxWidth().weight(1f).testTag(TrackSlots.Map))
    if (state.coachOn) {
        CoachLine(state.coachCorner, state.coachCue, coach, praise = state.coachPraise, dim = state.coachDim)
    }
}

private fun DrawScope.drawCorner(corner: NextCorner, brake: Color, coast: Color, throttle: Color) {
    val all = (corner.left + corner.right).ifEmpty { corner.line }
    if (all.isEmpty()) return
    val minE = all.minOf { it.east } - MarginMeters
    val maxE = all.maxOf { it.east } + MarginMeters
    val minN = all.minOf { it.north } - MarginMeters
    val maxN = all.maxOf { it.north } + MarginMeters
    val scale = minOf(size.width / (maxE - minE), size.height / (maxN - minN))
    // Centred, north up: the screen's y grows down.
    val dx = (size.width - (maxE - minE) * scale) / 2
    val dy = (size.height - (maxN - minN) * scale) / 2
    fun at(p: MapPoint) = Offset(dx + (p.east - minE) * scale, dy + (maxN - p.north) * scale)
    if (corner.left.size >= 2 && corner.right.size >= 2) {
        val road = Path().apply {
            (corner.left + corner.right.asReversed()).forEachIndexed { i, p ->
                val o = at(p)
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }
        drawPath(road, RoadColor)
    }
    val width = LineWidthMeters * scale
    for (i in 1 until corner.line.size) {
        val colour = when (corner.pedals.getOrNull(i - 1)) {
            MapPedal.Brake -> brake
            MapPedal.Throttle -> throttle
            MapPedal.Coast, null -> coast
        }
        drawLine(colour, at(corner.line[i - 1]), at(corner.line[i]), width, StrokeCap.Round)
    }
    corner.car?.let {
        val r = CarRadiusMeters * scale
        drawCircle(CarColor, r, at(it))
        drawCircle(Color.Black, r, at(it), style = Stroke(r * CarOutline))
    }
}

/** Space around the road, in metres. */
private const val MarginMeters = 8f

/** The target line's width and the car dot's radius, in metres: a road is about 10 m wide. */
private const val LineWidthMeters = 1.6f
private const val CarRadiusMeters = 2.4f

/** The car dot's black outline, as a part of its radius. */
private const val CarOutline = 0.3f

/** The road the laps used (UI-172 C), as on the tablet's map. */
private val RoadColor = Color(0xFF20282E)

/** The car dot: the app's amber accent (UI-172 C). */
private val CarColor = Color(0xFFFCB203)

/** Example next corner for the map screen: a made-up right-hander. Not real data. */
@Suppress("MagicNumber") // A drawing, not data.
val ExampleNextCorner: NextCorner = run {
    val steps = 24
    val radius = 40f
    fun arc(r: Float) = (0..steps).map { i ->
        val a = Math.PI * i / steps
        MapPoint((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
    }
    val line = (0..steps).map { i ->
        val a = Math.PI * i / steps
        // Wide on the way in, at the inside edge at the apex, out to the edge on the way out.
        val r = radius + 4f * abs(cos(a)).toFloat()
        MapPoint((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
    }
    val pedals = (0 until steps).map { i ->
        when {
            i < steps / 3 -> MapPedal.Brake
            i < steps / 2 -> MapPedal.Coast
            else -> MapPedal.Throttle
        }
    }
    NextCorner("T1", arc(radius + 6f), arc(radius - 4f), line, pedals, car = line[2])
}

@Preview(name = "Track screen, map screen", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenMapPreview() {
    BetterDriverTheme(track = true) {
        TrackScreenContent(ExampleTrackState.copy(mapOn = true, nextCorner = ExampleNextCorner), page = MapPage)
    }
}
