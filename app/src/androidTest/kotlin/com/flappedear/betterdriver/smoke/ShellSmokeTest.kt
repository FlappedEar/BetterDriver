package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The app's other screens on the emulator (FBBD-161): Drive, the bottom bar between every pair of
 * top-level destinations, every Settings tab, adding equipment without Bluetooth, the spoken coach
 * settings, a new car, the track list and the track editor of both kinds, and the backup dialog.
 */
@RunWith(AndroidJUnit4::class)
class ShellSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun driveAsksForAReceiver() {
        // The seeded setup has no GNSS receiver: Start is off and Drive says how to add one.
        app.waitForText("BetterDriver").assertIsDisplayed()
        app.waitForText("START").assertIsDisplayed()
        app.waitForText("Add a GNSS receiver to start").assertIsDisplayed()
        app.waitFor(tab("Drive")).assertIsDisplayed()
    }

    @Test
    fun bottomBarBothWays() {
        // Every top-level destination from each of the others, both ways round.
        val shows = mapOf(
            "Drive" to hasText("START"),
            "Sessions" to hasText("Import session"),
            "Settings" to tab("Devices"),
        )
        for (to in listOf("Sessions", "Settings", "Drive", "Settings", "Sessions", "Drive")) {
            app.openTab(to)
            app.waitFor(tab(to)).assertIsSelected()
            app.waitFor(shows.getValue(to)).assertIsDisplayed()
        }
        // Back from a top-level destination goes to Drive.
        app.openTab("Sessions")
        app.back()
        app.waitFor(tab("Drive")).assertIsSelected()
        app.waitForText("START").assertIsDisplayed()
    }

    @Test
    fun devicesAddEquipmentWithoutBluetooth() {
        app.openTab("Settings")
        app.openTab("Devices")
        app.waitForText("+ Add equipment").performClick()
        // The Bluetooth search finds nothing on the emulator; the rest is added without it.
        app.waitForText("FOUND NEARBY").assertIsDisplayed()
        app.waitForText("ADD WITHOUT BLUETOOTH").performScrollTo()

        // A Wi-Fi receiver asks for its address, a GoPro started by hand for its model.
        app.waitForText("NMEA or RaceChrono DIY over Wi-Fi").performScrollTo().performClick()
        app.waitForText("Join the receiver's Wi-Fi first", substring = true)
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("Join the receiver's Wi-Fi first", substring = true)
        app.waitForText("HERO8 or older, MAX").performScrollTo().performClick()
        app.waitForText("Which GoPro")
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("Which GoPro")

        // Demo GNSS: added, opened from the list, removed.
        app.waitForText("For trying the app").performScrollTo().performClick()
        app.waitFor(hasText("Demo GNSS") and hasClickAction()).performClick()
        app.waitForText("Made-up positions on a circle", substring = true)
        app.waitForText("Remove").performClick()
        app.waitUntilGone("Demo GNSS")
        app.waitForText("+ Add equipment")
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
        // Its row in My tracks; Where you are may name it too.
        app.waitFor(hasText(SmokeFixtures.CircuitName) and hasClickAction()).performClick()
        app.waitForText("Kind", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("re-timed", substring = true)
        app.onNode(hasText("Point to point") and hasClickAction()).performClick()
        app.waitForText("Place both to time runs", substring = true)
        app.back()
        app.waitForText("Discard changes?")
        app.waitForText("Discard").performClick()
        app.waitForText("MY TRACKS")

        // The hill: point to point, with its start and finish lines.
        app.waitFor(hasText(SmokeFixtures.HillName) and hasClickAction()).performClick()
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
        check(!app.showsText("Backed up."))
    }
}
