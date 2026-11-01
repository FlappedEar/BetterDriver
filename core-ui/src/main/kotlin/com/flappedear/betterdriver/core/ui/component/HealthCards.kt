@file:Suppress("TooManyFunctions") // The pieces of one card style.

package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.core.ui.InterFont

// The card style outside the car (UI-307 A, UI-308 A, Arek 2026-10-30): Apple Health's layout on
// true black, in amber only. Big bold page titles, section titles ("Pinned", "Highlights"), cards
// with a small amber heading (icon and name) and the time on the right, one big value with a small
// unit and a tiny chart beside it. The track screen does not use these.

/** The page title of a top-level screen: "Drive", "Sessions", or a session's time (UI-307 A). */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = LargeTitleStyle, color = MaterialTheme.colorScheme.onBackground)
}

/** "Pinned", "Highlights": the title above a group of cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = SectionTitleStyle, color = MaterialTheme.colorScheme.onBackground)
}

/** A card's icon, drawn as a line in the heading's colour. */
enum class HealthIcon(internal val path: String) {
    /** A stopwatch: laps and lap times. */
    Laps("M4.5 13a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0M12 13V9M10 3h4"),

    /** A line into a corner: the coach's next focus. */
    Coach("M4 15c3-8 7-10 16-10M18.5 5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),

    /** A heart: heart rate. */
    Heart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"),

    /** A car: the car's data. */
    Car("M4 16v-3l2.2-5h11.6L20 13v3zM7 16v2.5M17 16v2.5"),

    /** A phone: the devices a session uses. */
    Device("M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2zM11 18h2"),
    ;

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

/**
 * One card (UI-307 A): a heading with [icon] and [title] in the accent colour on the left, [trailing]
 * (a time, a lap) in grey with › on the right when the card opens something ([onClick]), then
 * [content]. Without a [title] the card has no heading.
 */
@Composable
fun HealthCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: HealthIcon? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(HealthCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .let { if (onClick != null) it.clickable(onClickLabel = onClickLabel, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) HealthHeading(title, icon, trailing, onClick != null)
        content()
    }
}

/** The shape of a card: 12 dp corners. */
val HealthCardShape = RoundedCornerShape(12.dp)

@Composable
private fun HealthHeading(title: String, icon: HealthIcon?, trailing: String?, opens: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it.vector, contentDescription = null, Modifier.size(18.dp), tint = accent) }
        Text(title, Modifier.weight(1f), style = HeadingStyle, color = accent, maxLines = 1)
        val side = listOfNotNull(trailing, "›".takeIf { opens }).joinToString(" ")
        if (side.isNotEmpty()) {
            Text(side, style = HealthDetailStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The card's big value with its small grey [unit]: "129 bpm avg", "1:49.91". */
@Composable
fun HealthValue(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 28.dp,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(value) }
            if (unit != null) {
                val small = SpanStyle(dim, HealthDetailStyle.fontSize, FontWeight.Medium)
                withStyle(small) { append(" $unit") }
            }
        },
        modifier,
        style = ValueStyle.copy(fontSize = size.value.sp),
        maxLines = 1,
    )
}

/** A grey line under a card's value. */
@Composable
fun HealthDetail(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier,
        style = HealthDetailStyle,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

/** The card's body: [text] on the left, a tiny [chart] at the bottom right. */
@Composable
fun HealthValueRow(
    modifier: Modifier = Modifier,
    chart: (@Composable () -> Unit)? = null,
    text: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), content = text)
        chart?.invoke()
    }
}

/**
 * A tiny bar chart of times (UI-307 A): one bar per value, the shortest time tallest and in [best]
 * (purple, the best lap's colour), the others in [color] at half strength.
 */
@Composable
fun MiniBars(values: List<Double>, color: Color, best: Color, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val lo = values.min()
    val hi = values.max()
    Canvas(modifier.width(MiniChartWidth).height(MiniChartHeight)) {
        val slot = size.width / values.size
        val gap = slot * BarGapShare
        values.forEachIndexed { i, v ->
            val share = if (hi > lo) 1 - (v - lo) / (hi - lo) else 1.0
            val h = (size.height * MinBarShare + share * size.height * (1 - MinBarShare)).toFloat()
            drawRoundRect(
                if (v == lo) best else color.copy(alpha = OtherBarAlpha),
                topLeft = Offset(i * slot + gap / 2, size.height - h),
                size = Size(slot - gap, h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** A tiny line chart of [values] in [color], higher values higher, with a dot at the last one. */
@Composable
fun MiniLine(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max()
    Canvas(modifier.width(MiniChartWidth).height(MiniChartHeight)) {
        val pad = 4.dp.toPx()
        fun at(i: Int): Offset {
            val share = if (hi > lo) (values[i] - lo) / (hi - lo) else 0.5
            val y = size.height - pad - share * (size.height - 2 * pad)
            return Offset(i * size.width / (values.size - 1), y.toFloat())
        }
        val line = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until values.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 3.dp.toPx(), at(values.size - 1))
    }
}

/** One bar of a comparison (UI-307 A): a session's [label], its [value] and a bar [share] of the longest. */
data class CompareBar(val label: String, val value: String, val share: Float, val color: Color)

/** Two or more sessions compared by bars, as the Highlights card sets this session against the one before. */
@Composable
fun CompareBars(bars: List<CompareBar>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bars.forEach { bar ->
            Column {
                HealthDetail(bar.label)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        bar.value,
                        Modifier.width(CompareValueWidth),
                        style = CompareValueStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f).height(8.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(bar.share.coerceIn(0f, 1f) * CompareBarMaxShare)
                                .height(8.dp)
                                .background(bar.color, RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

private val MiniChartWidth = 96.dp
private val MiniChartHeight = 40.dp
private const val BarGapShare = 0.25f
private const val MinBarShare = 0.1
private const val OtherBarAlpha = 0.55f
private const val CompareBarMaxShare = 0.85f
private val CompareValueWidth = 76.dp

private val LargeTitleStyle = inter(FontWeight.Bold, 32, lineHeight = 38)
private val SectionTitleStyle = inter(FontWeight.Bold, 20, lineHeight = 26)
private val HeadingStyle = inter(FontWeight.SemiBold, 15)
private val ValueStyle = TextStyle(
    fontFamily = InterFont,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    lineHeight = 34.sp,
)
private val CompareValueStyle = inter(FontWeight.Bold, 15).copy(fontFeatureSettings = "tnum")

/** The grey line's style, also for the trailing time in a heading. */
val HealthDetailStyle = inter(FontWeight.Normal, 14, lineHeight = 19)

private fun inter(weight: FontWeight, size: Int, lineHeight: Int? = null) = TextStyle(
    fontFamily = InterFont,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight?.sp ?: TextStyle.Default.lineHeight,
)
