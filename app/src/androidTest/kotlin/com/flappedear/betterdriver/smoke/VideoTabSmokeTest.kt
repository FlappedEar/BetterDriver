package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.videoexport.SaveVideoTag
import com.flappedear.betterdriver.videoexport.WatchVideoTag
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * A session's Video tab on the emulator (FBBD-392), as the iOS app's: a session with video has it
 * after Car, with Video with data's parts, Save video and Watch; Watch plays the picked lap with
 * the data band, and back returns to the tab. A session without video has no Video tab.
 */
@RunWith(AndroidJUnit4::class)
class VideoTabSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Test
    fun aSessionWithVideoWatchesALapFromItsVideoTab() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        app.openTab("Video")
        app.waitForText("Whole session", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitFor(hasTestTag(SaveVideoTag)).performScrollTo().assertIsEnabled()
        // The best lap is picked to start with, so Watch plays it.
        app.waitFor(hasTestTag(WatchVideoTag)).performScrollTo().assertIsEnabled().performClick()
        app.waitFor(Band, LoadTimeoutMs)
        app.waitForText("0:00 / ", substring = true)
        app.back()
        app.waitForText("Whole session", timeoutMs = LoadTimeoutMs)
    }

    @Test
    fun aSessionWithoutVideoHasNoVideoTab() {
        app.openSessions()
        app.openSession(SmokeFixtures.PlainCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        app.waitFor(tab("Car"))
        check(!app.exists(tab("Video"))) { "a session without video has a Video tab" }
    }

    private companion object {
        val Band = hasContentDescription("Data: ", substring = true)
    }
}
