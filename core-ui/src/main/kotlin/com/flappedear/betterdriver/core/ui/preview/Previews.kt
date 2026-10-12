package com.flappedear.betterdriver.core.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackColorsBlueOrange

// Previews of the theme (light, dark, track) and the shared track components
// (FBBD-38, FBBD-39). The screenshot tests record the same samples.

@Preview(name = "Theme, light")
@Composable
private fun ThemeLightPreview() {
    BetterDriverTheme(darkTheme = false) { ThemeSample() }
}

@Preview(name = "Theme, dark")
@Composable
private fun ThemeDarkPreview() {
    BetterDriverTheme(darkTheme = true) { ThemeSample() }
}

@Preview(name = "Theme, track")
@Composable
private fun ThemeTrackPreview() {
    BetterDriverTheme(track = true) {
        Column {
            ThemeSample()
            TrackColorsSample()
        }
    }
}

@Preview(name = "Theme, track, blue and orange")
@Composable
private fun ThemeTrackBlueOrangePreview() {
    BetterDriverTheme(track = true, trackColors = TrackColorsBlueOrange) { TrackColorsSample() }
}

@Preview(name = "ValueTile")
@Composable
private fun ValueTilePreview() {
    BetterDriverTheme(track = true) { ValueTileSample() }
}

@Preview(name = "AlertBanner")
@Composable
private fun AlertBannerPreview() {
    BetterDriverTheme(track = true) { AlertBannerSample() }
}

@Preview(name = "SectorStrip")
@Composable
private fun SectorStripPreview() {
    BetterDriverTheme(track = true) { SectorStripSample() }
}

@Preview(name = "DeltaDisplay")
@Composable
private fun DeltaDisplayPreview() {
    BetterDriverTheme(track = true) { DeltaDisplaySample() }
}
