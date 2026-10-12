package com.flappedear.betterdriver.core.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** UI-02 option C (approved): Saira Semi Condensed for numbers. Draw numbers with NumberText. */
val NumberFont = FontFamily(
    Font(R.font.saira_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.saira_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.saira_semi_condensed_bold, FontWeight.Bold),
)

/** Labels and running text. */
val LabelFont = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)


/**
 * Text size that ignores the system font scale. The track screen is a fixed
 * layout: a larger system font would push values out of their slots.
 */
@Composable
@ReadOnlyComposable
fun Dp.fixedSp(): TextUnit = with(LocalDensity.current) { (value / fontScale).sp }

@Composable
@ReadOnlyComposable
fun numberStyle(size: Dp, weight: FontWeight = FontWeight.Bold): TextStyle = TextStyle(
    fontFamily = NumberFont,
    fontWeight = weight,
    fontSize = size.fixedSp(),
    lineHeight = size.fixedSp(),
    // A line exactly the size, digits centred in it. Without this a line took about 1.6 times the
    // size (this font's own ascent and descent), and short slots clipped the digits (FBBD-40).
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

@Composable
@ReadOnlyComposable
fun labelStyle(size: Dp): TextStyle = TextStyle(
    fontFamily = LabelFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.fixedSp(),
    letterSpacing = 0.08.em,
)

/** Plain running text on the track screen (status items, alerts, coach line). */
@Composable
@ReadOnlyComposable
fun textStyle(size: Dp, weight: FontWeight = FontWeight.SemiBold): TextStyle = TextStyle(
    fontFamily = LabelFont,
    fontWeight = weight,
    fontSize = size.fixedSp(),
)

/** Material typography for the non-track screens (settings, sessions, review). */
val BetterDriverTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = NumberFont),
        displayMedium = base.displayMedium.copy(fontFamily = NumberFont),
        displaySmall = base.displaySmall.copy(fontFamily = NumberFont),
        headlineLarge = base.headlineLarge.copy(fontFamily = LabelFont),
        headlineMedium = base.headlineMedium.copy(fontFamily = LabelFont),
        headlineSmall = base.headlineSmall.copy(fontFamily = LabelFont),
        titleLarge = base.titleLarge.copy(fontFamily = LabelFont),
        titleMedium = base.titleMedium.copy(fontFamily = LabelFont),
        titleSmall = base.titleSmall.copy(fontFamily = LabelFont),
        bodyLarge = base.bodyLarge.copy(fontFamily = LabelFont),
        bodyMedium = base.bodyMedium.copy(fontFamily = LabelFont),
        bodySmall = base.bodySmall.copy(fontFamily = LabelFont),
        labelLarge = base.labelLarge.copy(fontFamily = LabelFont),
        labelMedium = base.labelMedium.copy(fontFamily = LabelFont),
        labelSmall = base.labelSmall.copy(fontFamily = LabelFont),
    )
}
