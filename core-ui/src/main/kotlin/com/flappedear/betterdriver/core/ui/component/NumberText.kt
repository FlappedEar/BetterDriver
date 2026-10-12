package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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
    val cell = remember(style, density) {
        val widest = Digits.maxOf { measurer.measure(it.toString(), style).size.width }
        with(density) { widest.toDp() }
    }
    Row(
        modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEach { ch ->
            if (ch.isDigit() || ch == DigitPlaceholder) {
                Box(Modifier.width(cell), contentAlignment = Alignment.Center) { Glyph(ch, style, color) }
            } else {
                Glyph(ch, style, color)
            }
        }
    }
}

@Composable
private fun Glyph(ch: Char, style: TextStyle, color: Color) {
    Text(ch.toString(), style = style, color = color, maxLines = 1, softWrap = false)
}
