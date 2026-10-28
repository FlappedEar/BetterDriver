package com.flappedear.betterdriver.smoke

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Watch video (FBBD-341) on the emulator: a drive away from every saved track, so with no laps, still
 * plays its phone video in the app with the data band over it. The band is a drawing, so the test
 * reads what it shows from its description ("Data: 54 km/h"), which follows the same frame.
 */
@RunWith(AndroidJUnit4::class)
class WatchVideoSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Before
    fun seed() = SmokeData.seedCommute()

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun aDriveWithNoTrackPlaysItsVideoWithTheData() {
        app.openSessions()
        app.waitForText(SmokeFixtures.CommuteCar, substring = true)
        // The newest session is the drive; its sheet offers its video.
        app.openNewestSessionActions()
        app.waitForText("Watch video").performScrollTo().performClick()

        // Read, then shown from the start, paused, with the band: speed, and no lap.
        app.waitFor(Band, LoadTimeoutMs)
        app.waitForText("0:00 / ", substring = true)
        check(", lap" !in band()) { "a drive with no track showed a lap: ${band()}" }

        // Along the seek bar the band follows the drive: somewhere it is moving.
        val speeds = listOf(0.2f, 0.35f, 0.5f).map { at ->
            app.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(at) }
            app.waitForIdle()
            kmh(band())
        }
        check(speeds.any { it > 0 }) { "the band showed no speed along the drive: $speeds" }
        check(", lap" !in band()) { "a drive with no track showed a lap: ${band()}" }

        // It plays: the clock moves on. The screen follows every frame while it plays, so the clock
        // is advanced by hand.
        app.withManualClock {
            val before = clock()
            app.pumpUntilText("Play").performClick()
            app.pumpUntilText("Pause")
            app.pumpUntil(LoadTimeoutMs, "the video to play on") { clock() != before }
            app.pumpUntilText("Pause").performClick()
            app.pumpUntilText("Play")
        }

        // Back to Sessions.
        app.back()
        app.waitForText(SmokeFixtures.CommuteCar, substring = true)
    }

    /** What the band shows now. */
    private fun band(): String = app.onNode(Band).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** "1:27 / 4:50" now. */
    private fun clock(): String = app.onNode(
        SemanticsMatcher("the clock") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { " / " in it.text }
        },
    ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()

    /** The speed in the band's description, "Data: 54 km/h" -> 54; -1 without one. */
    private fun kmh(description: String): Int =
        Regex("""Data: (\d+) km/h""").find(description)?.groupValues?.get(1)?.toInt() ?: -1

    private companion object {
        val Band = hasContentDescription("Data: ", substring = true)
    }
}
