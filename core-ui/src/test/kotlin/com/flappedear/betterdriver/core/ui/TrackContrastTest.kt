package com.flappedear.betterdriver.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.flappedear.betterdriver.core.ui.component.TintFraction
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * High contrast on the track screen (plan UI principles: "large type, high
 * contrast and a dark theme"; FBBD-38). WCAG 2 contrast ratios for every
 * text and background pair the track components draw, in both palettes.
 */
class TrackContrastTest {
    private val palettes = mapOf("green/red" to TrackColorsGreenRed, "blue/orange" to TrackColorsBlueOrange)

    @Test
    fun mainTextIsAtLeast7To1() {
        palettes.forEach { (name, c) ->
            check(name, "text on background", c.text, c.background, Enhanced)
            check(name, "text on surface", c.text, c.surface, Enhanced)
            check(name, "delta on gain tint", c.text, lerp(c.surface, c.gain, TintFraction), Enhanced)
            check(name, "delta on loss tint", c.text, lerp(c.surface, c.loss, TintFraction), Enhanced)
        }
    }

    @Test
    fun everyOtherTextIsAtLeast4p5To1() {
        palettes.forEach { (name, c) ->
            check(name, "labels on surface", c.textDim, c.surface, Minimum)
            check(name, "labels on background", c.textDim, c.background, Minimum)
            check(name, "text on gain", c.onGain, c.gain, Minimum)
            check(name, "text on loss", c.onLoss, c.loss, Minimum)
            check(name, "text on warning", c.onWarning, c.warning, Minimum)
            // AlertBanner draws its detail line at 85 % alpha.
            check(name, "alert detail on loss", c.onLoss.copy(alpha = AlertDetailAlpha), c.loss, Minimum)
            check(name, "alert detail on warning", c.onWarning.copy(alpha = AlertDetailAlpha), c.warning, Minimum)
            listOf("gain" to c.gain, "loss" to c.loss, "warning" to c.warning, "cold" to c.cold, "best" to c.best)
                .forEach { (token, color) ->
                    check(name, "$token on surface", color, c.surface, Minimum)
                    check(name, "$token on background", color, c.background, Minimum)
                }
        }
    }

    @Test
    fun indicatorsAreAtLeast3To1() {
        palettes.forEach { (name, c) ->
            check(name, "ok dot", c.gain, c.background, NonText)
            check(name, "warning dot", c.warning, c.background, NonText)
            check(name, "recording dot", c.loss, c.background, NonText)
            check(name, "current sector outline", c.textDim, c.background, NonText)
        }
    }

    @Test
    fun materialSchemesPassAA() {
        val schemes = mapOf(
            "light" to LightScheme,
            "dark" to DarkScheme,
            "track" to trackScheme(TrackColorsGreenRed),
            "track blue/orange" to trackScheme(TrackColorsBlueOrange),
        )
        schemes.forEach { (name, s) -> checkScheme(name, s) }
    }

    private fun checkScheme(name: String, s: ColorScheme) {
        check(name, "onBackground", s.onBackground, s.background, Minimum)
        check(name, "onSurface", s.onSurface, s.surface, Minimum)
        check(name, "onSurfaceVariant", s.onSurfaceVariant, s.surface, Minimum)
        check(name, "onPrimary", s.onPrimary, s.primary, Minimum)
        check(name, "primary on background", s.primary, s.background, Minimum)
        check(name, "onError", s.onError, s.error, Minimum)
    }

    private fun check(palette: String, pair: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$palette: $pair is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.compositeOver(background).luminance() + LuminanceOffset
        val b = background.luminance() + LuminanceOffset
        return (max(a, b) / min(a, b)).toDouble()
    }

    private companion object {
        const val Enhanced = 7.0
        const val Minimum = 4.5
        const val NonText = 3.0
        const val LuminanceOffset = 0.05f
        const val AlertDetailAlpha = 0.85f
    }
}
