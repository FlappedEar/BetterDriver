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

internal val LightScheme = lightColorScheme(
    primary = Accent,
    background = Color(0xFFF3F5F6),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14191D),
    onSurface = Color(0xFF14191D),
)

/**
 * App screens outside the car (UI-17 option A: always dark), colours from the round 2 mockups,
 * on a true black background: on the OLED phone screen black pixels are off.
 */
internal val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF06141A),
    background = Color(0xFF000000),
    surface = Color(0xFF151B20),
    surfaceVariant = Color(0xFF1C242A),
    surfaceContainer = Color(0xFF151B20),
    onBackground = Color(0xFFEEF2F4),
    onSurface = Color(0xFFEEF2F4),
    onSurfaceVariant = Color(0xFF8C9AA5),
    outline = Color(0xFF26313A),
    outlineVariant = Color(0xFF26313A),
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
        MaterialTheme(colorScheme = scheme, typography = BetterDriverTypography, content = content)
    }
}

/** Shortcut for the track colour tokens inside [BetterDriverTheme]. */
object TrackTheme {
    val colors: TrackColors
        @Composable get() = LocalTrackColors.current
}
