package com.flappedear.betterdriver.feature.track

import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackColorsBlueOrange
import com.flappedear.betterdriver.core.ui.TrackColorsGreenRed
import com.flappedear.betterdriver.core.ui.component.AlertLevel
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
        TrackScreenContent(ExampleTrackState)
    }

    /** UI-121 B: the second screen, three rows of four; its dot lit in the status strip (UI-122 A). */
    @Test
    fun secondScreen() = snap("track_screen_second") { TrackScreenContent(ExampleTrackState, page = 1) }

    /** FBBD-318: the G radar beside nine tiles (UI-240 A), rings only, no numbers, braking up. */
    @Test
    fun secondScreenWithRadar() = snap("track_screen_second_radar") {
        TrackScreenContent(ExampleTrackState.copy(radarOn = true, radar = ExampleRadar), page = 1)
    }

    @Test
    fun mapScreen() = snap("track_screen_map") {
        TrackScreenContent(ExampleTrackState.copy(mapOn = true, nextCorner = ExampleNextCorner), page = MapPage)
    }

    @Test
    fun coachOff() = snap("track_screen_coach_off") { TrackScreenContent(ExampleTrackState.copy(coachOn = false)) }

    /** UI-125 B: a new install shows speed and G, the other slots empty. */
    @Test
    fun newInstall() = snap("track_screen_new_install") {
        TrackScreenContent(
            ExampleTrackState.copy(
                tiles = listOf(TrackTile("Speed", "142", "km/h"), TrackTile("G", "1.12", "g")) + List(8) { null },
            ),
        )
    }

    /** UI-126 B: oil temperature has no data and is left out; the others in its row widen. */
    @Test
    fun tileWithoutData() = snap("track_screen_no_data") { TrackScreenContent(NoOil) }

    /** UI-123 B: with the car stopped every tile shows, so each can be held; no data is a dash. */
    @Test
    fun tileWithoutDataCarStopped() = snap("track_screen_no_data_stopped") {
        TrackScreenContent(NoOil.copy(editable = true))
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
        val NoOil = ExampleTrackState.copy(
            tiles = ExampleTrackState.tiles.map { t ->
                if (t != null && t.id == "obd_oil_temp") t.copy(hasData = false) else t
            },
        )

        val BeforeFirstLap = ExampleTrackState.copy(
            deltaSeconds = null,
            lapNumber = null,
            lapMillis = null,
            lastMillis = null,
            bestMillis = null,
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
