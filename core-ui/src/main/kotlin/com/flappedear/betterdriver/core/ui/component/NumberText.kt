package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.flappedear.betterdriver.core.ui.numberStyle

private const val Digits = "0123456789"

/**
 * Stands in for a digit not known yet ("-:--.--"): drawn in a digit's cell, so a placeholder
 * takes exactly the room of the number that replaces it and nothing moves (FBBD-62).
 */
const val DigitPlaceholder = '-'

/**
 * A number whose digits never move as they change (FBBD-39, FBBD-62).
 *
 * The approved number font (Saira Semi Condensed, UI-02 option C) has no
 * tabular figures, so each digit is drawn centred in a cell as wide as the
 * widest digit. Other characters (":", ".", "+", "−") keep their own width;
 * the formatters put them in fixed positions. Accessibility services read the
 * whole value as one node, not glyph by glyph.
 *
 * The track screen changes its numbers 4 times a second (FBBD-266), so each character is laid out
 * once per style and colour and kept; a new value only places and draws those layouts, in the
 * same pixels as a row of one text per character would.
 */
@Composable
fun NumberText(
    text: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
) {
    val style = numberStyle(size, weight)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphs = remember(style, density, measurer) { NumberGlyphs(measurer, style, density) }
    val line = glyphs.line(text, color)
    Spacer(
        modifier
            .clearAndSetSemantics { contentDescription = text }
            .layout { measurable, constraints ->
                val width = line.width.coerceIn(constraints.minWidth, constraints.maxWidth)
                val height = line.height.coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .drawBehind { line.draw(this) },
    )
}

/**
 * The laid-out characters of one number style, each kept per colour. Main thread only, as
 * composition is.
 */
private class NumberGlyphs(private val measurer: TextMeasurer, private val style: TextStyle, density: Density) {
    private val byColor = HashMap<Color, HashMap<Char, TextLayoutResult>>()

    /** A digit's cell: as wide as the widest digit, in whole pixels as a dp width rounds. */
    private val cell: Int = with(density) {
        Digits.maxOf { measurer.measure(it.toString(), style).size.width }.toDp().roundToPx()
    }

    fun line(text: String, color: Color): NumberLine {
        val laid = byColor.getOrPut(color) { HashMap() }
        val chars = text.map { ch ->
            laid.getOrPut(ch) {
                measurer.measure(ch.toString(), style.copy(color = color), maxLines = 1, softWrap = false)
            }
        }
        return NumberLine(text, chars, cell)
    }
}

/**
 * One number's characters placed as a row of one text per character places them: digits centred
 * in their cells, the others at their own width, all centred on the line's height.
 */
private class NumberLine(text: String, private val chars: List<TextLayoutResult>, cell: Int) {
    private val slots = IntArray(chars.size)
    private val lefts = IntArray(chars.size)
    val width: Int
    val height: Int = chars.maxOfOrNull { it.size.height } ?: 0

    init {
        var x = 0
        text.forEachIndexed { i, ch ->
            val glyph = chars[i].size.width
            val slot = if (ch.isDigit() || ch == DigitPlaceholder) cell else glyph
            slots[i] = x
            lefts[i] = ((slot - glyph) / 2f).roundToInt()
            x += slot
        }
        width = x
    }

    fun draw(scope: DrawScope) {
        val lineHeight = scope.size.height
        chars.forEachIndexed { i, glyph ->
            val top = ((lineHeight - glyph.size.height) / 2f).roundToInt()
            scope.drawText(glyph, topLeft = Offset((slots[i] + lefts[i]).toFloat(), top.toFloat()))
        }
    }
}

/**
 * How tall a line of [NumberText] is per dp of its size: the number font's own line height, which
 * is more than its size. Components that fit a number to a height divide by it (FBBD-62).
 */
@Composable
internal fun numberLineFactor(weight: FontWeight = FontWeight.Bold): Float {
    val style = numberStyle(ProbeSize, weight)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) { with(density) { measurer.measure("0", style).size.height.toDp() } / ProbeSize }
}

private val ProbeSize = 100.dp
