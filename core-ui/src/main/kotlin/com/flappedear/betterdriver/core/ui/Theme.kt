package com.flappedear.betterdriver.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF0B6E8A)
private val AccentDark = Color(0xFF4FB8D6)

private val LightScheme = lightColorScheme(
    primary = Accent,
    background = Color(0xFFF3F5F6),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14191D),
    onSurface = Color(0xFF14191D),
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    background = Color(0xFF0E1215),
    surface = Color(0xFF161C20),
    onBackground = Color(0xFFE8EDF0),
    onSurface = Color(0xFFE8EDF0),
)

private fun trackScheme(c: TrackColors) = darkColorScheme(
    primary = c.text,
    background = c.background,
    surface = c.surface,
    onBackground = c.text,
    onSurface = c.text,
    error = c.loss,
)

/**
 * App theme (FBBD-38). [track] selects the fixed dark, high-contrast variant
 * used on the in-car screen; other screens follow the system light/dark mode.
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
        MaterialTheme(colorScheme = scheme, typography = BetterDriverTypography, content = content)
    }
}

/** Shortcut for the track colour tokens inside [BetterDriverTheme]. */
object TrackTheme {
    val colors: TrackColors
        @Composable get() = LocalTrackColors.current
}
