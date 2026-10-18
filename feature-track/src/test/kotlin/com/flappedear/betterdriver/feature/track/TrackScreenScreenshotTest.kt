package com.flappedear.betterdriver.feature.track

import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackColorsBlueOrange
import com.flappedear.betterdriver.core.ui.TrackColorsGreenRed
import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.ValueState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Pixel 6 in landscape, as the track layout was designed. */
private const val Pixel6Landscape = "w914dp-h411dp-land-xhdpi"

/**
 * Screenshot goldens of the whole track screen with example state (FBBD-62),
 * on a Pixel 6 in landscape (914 x 411 dp, as the layout was designed).
 * Goldens live in src/test/screenshots; see README, "Screenshot tests".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Pixel6Landscape)
class TrackScreenScreenshotTest {
    @Test
    fun lapInProgress() = snap("track_screen") { TrackScreenContent(ExampleTrackState) }

    @Test
    fun beforeFirstLap() = snap("track_screen_before_first_lap") { TrackScreenContent(BeforeFirstLap) }

    @Test
    fun gnssLost() = snap("track_screen_gnss_lost") { TrackScreenContent(GnssLost) }

    @Test
    fun hotPhoneAndWarningTiles() = snap("track_screen_warnings") { TrackScreenContent(Warnings) }

    @Test
    fun blueOrangePalette() = snap("track_screen_blue_orange", TrackColorsBlueOrange) {
        TrackScreenContent(ExampleTrackState.copy(sectors = MixedSectors))
    }

    /** System bars shown by a swipe: 340 dp tall. The delta shrinks, nothing else moves. */
    @Test
    @Config(qualifiers = "w914dp-h340dp-land-xhdpi")
    fun shortWindow() = snap("track_screen_short") { TrackScreenContent(ExampleTrackState) }

    private fun snap(name: String, colors: TrackColors = TrackColorsGreenRed, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png") {
            BetterDriverTheme(track = true, trackColors = colors, content = content)
        }
    }

    private companion object {
        val MixedSectors = listOf(
            SectorState("S1", -0.21),
            SectorState("S2", 0.08),
            SectorState("S3", -0.05),
            SectorState("S4", null, current = true),
        )

        val BeforeFirstLap = ExampleTrackState.copy(
            deltaSeconds = null,
            lapNumber = null,
            lapMillis = null,
            lastMillis = null,
            bestMillis = null,
            sectors = listOf(
                SectorState("S1", null, current = true),
                SectorState("S2", null),
                SectorState("S3", null),
            ),
            coachCorner = null,
            coachCue = null,
        )

        val GnssLost = ExampleTrackState.copy(
            alert = TrackAlert("GNSS signal lost", "Lap timing paused · video still recording", AlertLevel.Critical),
        )

        val Warnings = ExampleTrackState.copy(
            deltaSeconds = 1.27,
            alert = TrackAlert("Phone hot", "Video quality lowered · data still recording", AlertLevel.Warning),
            tiles = listOf(
                TrackTile("Speed", "98", "km/h"),
                TrackTile("Oil", "124", "°C", ValueState.Warning),
                TrackTile("Water", "108", "°C", ValueState.Critical),
            ),
        )
    }
}
