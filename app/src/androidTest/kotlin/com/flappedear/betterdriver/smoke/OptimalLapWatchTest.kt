package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.videoexport.WatchVideoTag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The composite laps watched in the app (FBBD-178): [ReviewFixtures]' session A, whose phone video
 * covers both timed laps. On its Video tab the realistic optimal and the raw theoretical best can each
 * be picked and watched, and the data band names the composite lap, not the lap filmed.
 */
@RunWith(AndroidJUnit4::class)
class OptimalLapWatchTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Before
    fun seed() = SmokeData.seedReview()

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun bothCompositeLapsAreWatchedFromTheVideoTab() {
        app.openSessions()
        app.openSession(ReviewFixtures.SyncCarA)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        app.openTab("Video")
        for ((row, caption) in Composites) {
            app.waitForText(row, substring = true, timeoutMs = LoadTimeoutMs).performScrollTo().performClick()
            app.waitFor(hasTestTag(WatchVideoTag)).performScrollTo().assertIsEnabled().performClick()
            app.waitFor(hasContentDescription(caption, substring = true), LoadTimeoutMs)
            app.waitForText("0:00 / ", substring = true)
            app.back()
            app.waitForText("Whole session", timeoutMs = LoadTimeoutMs)
        }
    }

    private companion object {
        /** Each composite lap's row on the Video tab, and what its data band starts its caption with. */
        val Composites = listOf(
            "Realistic optimal · " to "REALISTIC OPTIMAL",
            "Raw theoretical best · " to "RAW THEORETICAL",
        )
    }
}
