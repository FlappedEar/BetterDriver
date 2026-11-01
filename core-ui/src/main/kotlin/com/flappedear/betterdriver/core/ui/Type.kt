package com.flappedear.betterdriver.core.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Text on the screens outside the car (UI-307 A, Arek 2026-10-30: "Apple Health ma całkiem fajny
 * design"): Inter, the closest free font to Apple's. The track screen keeps Saira and Barlow.
 */
val InterFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
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

/** Material typography of the track screen: Saira for display numbers, Barlow for the rest. */
val TrackTypography: Typography = typography(display = NumberFont, text = LabelFont)

/**
 * Material typography for the non-track screens (settings, sessions, review): Inter throughout
 * (UI-307 A), its digits tabular so times line up.
 */
val BetterDriverTypography: Typography = typography(display = InterFont, text = InterFont)

private fun typography(display: FontFamily, text: FontFamily): Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = display),
        displayMedium = base.displayMedium.copy(fontFamily = display),
        displaySmall = base.displaySmall.copy(fontFamily = display),
        headlineLarge = base.headlineLarge.copy(fontFamily = text),
        headlineMedium = base.headlineMedium.copy(fontFamily = text),
        headlineSmall = base.headlineSmall.copy(fontFamily = text),
        titleLarge = base.titleLarge.copy(fontFamily = text),
        titleMedium = base.titleMedium.copy(fontFamily = text),
        titleSmall = base.titleSmall.copy(fontFamily = text),
        bodyLarge = base.bodyLarge.copy(fontFamily = text),
        bodyMedium = base.bodyMedium.copy(fontFamily = text),
        bodySmall = base.bodySmall.copy(fontFamily = text),
        labelLarge = base.labelLarge.copy(fontFamily = text),
        labelMedium = base.labelMedium.copy(fontFamily = text),
        labelSmall = base.labelSmall.copy(fontFamily = text),
    )
}
