package com.flappedear.betterdriver.core.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackColorsBlueOrange
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot goldens of the theme and the shared components (FBBD-38, FBBD-39),
 * drawn from the same samples as the previews. Goldens live in
 * src/test/screenshots; record them with the Screenshots workflow and CI
 * compares against them (README, "Screenshot tests").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h720dp-xhdpi")
class ComponentScreenshotTest {
    @Test
    fun themeLight() = snap("theme_light") { BetterDriverTheme(darkTheme = false) { ThemeSample() } }

    @Test
    fun themeDark() = snap("theme_dark") { BetterDriverTheme(darkTheme = true) { ThemeSample() } }

    @Test
    fun themeTrack() = snap("theme_track") {
        BetterDriverTheme(track = true) {
            Column {
                ThemeSample()
                TrackColorsSample()
            }
        }
    }

    @Test
    fun themeTrackBlueOrange() = snap("theme_track_blue_orange") {
        BetterDriverTheme(track = true, trackColors = TrackColorsBlueOrange) { TrackColorsSample() }
    }

    @Test
    fun valueTile() = snap("value_tile") { BetterDriverTheme(track = true) { ValueTileSample() } }

    @Test
    fun alertBanner() = snap("alert_banner") { BetterDriverTheme(track = true) { AlertBannerSample() } }

    @Test
    fun sectorStrip() = snap("sector_strip") { BetterDriverTheme(track = true) { SectorStripSample() } }

    @Test
    fun deltaDisplay() = snap("delta_display") { BetterDriverTheme(track = true) { DeltaDisplaySample() } }

    private fun snap(name: String, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png", content = content)
    }
}
