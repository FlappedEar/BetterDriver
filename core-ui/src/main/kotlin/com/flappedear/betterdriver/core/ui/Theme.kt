package com.flappedear.betterdriver.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * FlappedEar amber (UI-144 B): buttons, links, the selected tab, START and the cards' headings.
 * #FFC044 since UI-307 A, the amber of the Health-like mockups Arek picked.
 */
private val Accent = Color(0xFF8A5F00)
private val AccentDark = Color(0xFFFFC044)

internal val LightScheme = lightColorScheme(
    primary = Accent,
    background = Color(0xFFF3F5F6),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14191D),
    onSurface = Color(0xFF14191D),
)

/** A card on black (UI-307 A): Apple's grouped background in dark mode. */
private val CardGrey = Color(0xFF1C1C1E)

/** A control or a selected row on a card. */
private val RaisedGrey = Color(0xFF2C2C2E)

/**
 * App screens outside the car (UI-17 option A: always dark), on a true black background: on the
 * OLED phone screen black pixels are off (Arek, 2026-10-23). The Health-like look (UI-307 A, UI-308 A):
 * cards in #1C1C1E, grey text #8E8E93, separators #38383A, amber the only accent. Every container
 * colour is set, so no Material default (grey-violet) shows in a sheet, a dialog, a tonal button or
 * the bar's indicator.
 */
internal val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF211B0F),
    primaryContainer = RaisedGrey,
    onPrimaryContainer = AccentDark,
    secondary = AccentDark,
    onSecondary = Color(0xFF211B0F),
    secondaryContainer = RaisedGrey,
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = AccentDark,
    onTertiary = Color(0xFF211B0F),
    tertiaryContainer = RaisedGrey,
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = CardGrey,
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = RaisedGrey,
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceTint = Color.Transparent,
    surfaceBright = RaisedGrey,
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = CardGrey,
    surfaceContainer = CardGrey,
    surfaceContainerHigh = CardGrey,
    surfaceContainerHighest = RaisedGrey,
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Accent,
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF38383A),
    scrim = Color(0xFF000000),
)

internal fun trackScheme(c: TrackColors) = darkColorScheme(
    primary = c.text,
    onPrimary = c.background,
    background = c.background,
    surface = c.surface,
    onBackground = c.text,
    onSurface = c.text,
    error = c.loss,
    // The Material default (0xFF601410) is 4:1 on the loss red.
    onError = c.onLoss,
)

/**
 * App theme (FBBD-38). [track] selects the fixed dark, high-contrast variant
 * used on the in-car screen. The app passes `darkTheme = true` everywhere
 * (UI-17: always dark); the light scheme is kept for previews and later use.
 */
@Composable
fun BetterDriverTheme(
    track: Boolean = false,
    trackColors: TrackColors = TrackColorsGreenRed,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = when {
        track -> trackScheme(trackColors)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalTrackColors provides trackColors) {
        // The track screen keeps its fonts (UI-02 C); the screens outside the car use Inter (UI-307 A).
        val typography = if (track) TrackTypography else BetterDriverTypography
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Shortcut for the track colour tokens inside [BetterDriverTheme]. */
object TrackTheme {
    val colors: TrackColors
        @Composable get() = LocalTrackColors.current
}
