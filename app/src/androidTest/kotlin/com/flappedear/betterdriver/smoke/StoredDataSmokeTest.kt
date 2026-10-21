package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Data as older builds stored it, opened by this one on the emulator (SmokeFixtures.seedOld): a
 * track file from before point-to-point tracks and a session from before imported sessions. The
 * format readers themselves are checked on the JVM (TrackTest, StoredFormatTest, SetupTest).
 */
@RunWith(AndroidJUnit4::class)
class StoredDataSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun oldSessionAndTrackOpen() {
        SmokeData.seedOld()
        app.openSessions()
        app.openSession(SmokeFixtures.OldCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("REALISTIC OPTIMAL").assertIsDisplayed()
        app.waitForText("RAW THEORETICAL BEST").assertIsDisplayed()
        app.openTab("Laps")
        app.waitForText("Lap 2")
        app.back()

        // The old track file reads as a circuit, with its line.
        app.openSettingsTopic("Tracks")
        app.waitFor(hasText(SmokeFixtures.OldTrackName) and hasClickAction()).performClick()
        app.waitForText("Kind", timeoutMs = LoadTimeoutMs)
        app.waitFor(hasText("Circuit") and hasClickAction()).assertIsSelected()
        app.back()
        app.waitForText("MY TRACKS")
    }
}
