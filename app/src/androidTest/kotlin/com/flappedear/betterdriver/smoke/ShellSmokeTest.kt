package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The app's other screens on the emulator (FBBD-161): Drive, every Settings tab, the spoken coach
 * settings, a new car, the track list and the track editor of both kinds, and the backup dialog.
 */
@RunWith(AndroidJUnit4::class)
class ShellSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Test
    fun driveShowsTheLastSession() {
        app.waitForText("BetterDriver").assertIsDisplayed()
        app.waitForText("LAST SESSION").assertIsDisplayed()
        app.waitFor(tab("Drive")).assertIsDisplayed()
    }

    @Test
    fun everySettingsTab() {
        app.openTab("Settings")
        app.openTab("Devices")
        app.openTab("Car")
        app.waitForText("Units").assertIsDisplayed()
        app.openTab("Recording")
        app.waitForText("Tracks and timing lines").assertIsDisplayed()
        app.waitForText("2 tracks").assertIsDisplayed()
        app.openTab("App")
        app.waitForText("Version").assertIsDisplayed()
        app.waitForText("Back up").performScrollTo().assertIsDisplayed()
        app.waitForText("Restore").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun spokenCoachSettings() {
        app.openTab("Settings")
        app.openTab("App")
        app.waitForText("Spoken coach").performScrollTo().performClick()
        app.waitForText("Picked corners").assertIsDisplayed()
        app.waitForText("Learning laps").performScrollTo().assertIsDisplayed()
        app.back()
        app.waitForText("Version")
    }

    @Test
    fun newCar() {
        app.openTab("Settings")
        app.openTab("Car")
        app.waitForText("+ Add car").performScrollTo().performClick()
        app.waitForText("The name goes into each session and its VBO export.").assertIsDisplayed()
        app.back()
        app.waitForText("Units")
    }

    @Test
    fun trackListAndEditorOfBothKinds() {
        app.openTab("Settings")
        app.openTab("Recording")
        app.waitForText("Tracks and timing lines").performClick()
        app.waitForText("MY TRACKS").assertIsDisplayed()
        app.waitForText("Point to point · 1 sector").assertIsDisplayed()

        // The circuit: its kind can be changed to point to point; leaving asks before dropping it.
        app.waitForText(SmokeFixtures.CircuitName).performClick()
        app.waitForText("Kind", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("re-timed", substring = true)
        app.onNode(hasText("Point to point") and hasClickAction()).performClick()
        app.waitForText("Place both to time runs", substring = true)
        app.back()
        app.waitForText("Discard changes?")
        app.waitForText("Discard").performClick()
        app.waitForText("MY TRACKS")

        // The hill: point to point, with its start and finish lines.
        app.waitForText(SmokeFixtures.HillName).performClick()
        app.waitForText("Kind", timeoutMs = LoadTimeoutMs)
        app.waitForText("Runs go from the start line to the finish line; the drive back is not timed.")
        app.waitForText("re-timed", substring = true)
        app.back()
        app.waitForText("MY TRACKS")
    }

    @Test
    fun backupDialogOpensAndCancels() {
        app.openTab("Settings")
        app.openTab("App")
        app.waitForText("Back up").performScrollTo().performClick()
        app.waitForText("Include sessions (without video)").assertIsDisplayed()
        app.waitForText("3 sessions", substring = true, timeoutMs = LoadTimeoutMs)
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("Include sessions (without video)")
        check(app.onAllNodesWithText("Backed up.").fetchSemanticsNodes().isEmpty())
    }
}
