package com.flappedear.betterdriver.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens for the in-car track screen (UI-01).
 *
 * The track screen is always dark and does not follow the system theme.
 * Gain and loss are the only bright colours; amber is a warning, blue is cold,
 * purple marks the best lap. The same token always carries the same meaning.
 *
 * High contrast (plan UI principles, FBBD-38): every text colour reaches 7:1
 * (WCAG AAA) on the colour it is drawn on, as a proxy for reading it in
 * sunlight (FBBD-65).
 * The `on*` colours are the text on a filled gain, loss or warning area.
 * TrackContrastTest checks every pair the components draw.
 */
@Immutable
data class TrackColors(
    val background: Color,
    val surface: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val gain: Color,
    val onGain: Color,
    val loss: Color,
    val onLoss: Color,
    val warning: Color,
    val onWarning: Color,
    val cold: Color,
    val best: Color,
)

/** UI-01 option A: green gain, red loss. */
val TrackColorsGreenRed = TrackColors(
    background = Color(0xFF07090B),
    surface = Color(0xFF12171B),
    line = Color(0xFF232B31),
    text = Color(0xFFF2F5F7),
    textDim = Color(0xFF96A5B0),
    gain = Color(0xFF22D37A),
    onGain = Color(0xFF071009),
    loss = Color(0xFFFF6863),
    // Dark, like onGain: white on this red is only 2.8:1.
    onLoss = Color(0xFF100404),
    warning = Color(0xFFFFB020),
    onWarning = Color(0xFF141006),
    cold = Color(0xFF51A7FF),
    best = Color(0xFFC58BFF),
)

/** UI-01 option B: blue gain, orange loss, for red-green colour blindness. */
val TrackColorsBlueOrange = TrackColorsGreenRed.copy(
    gain = Color(0xFF3CA2FF),
    onGain = Color(0xFF06101A),
    loss = Color(0xFFFF8A1F),
    onLoss = Color(0xFF1A0E02),
)

val LocalTrackColors = staticCompositionLocalOf { TrackColorsGreenRed }
