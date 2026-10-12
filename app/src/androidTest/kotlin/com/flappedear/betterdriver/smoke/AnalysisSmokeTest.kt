package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Sessions and lap analysis on the emulator (FBBD-161): every analysis tab of a circuit session
 * with video, the map, Corners → Every lap → Corner laps (with its video and Compare), the coach
 * picks, the optimal lap video's planner (FBBD-163), and a point-to-point session with its
 * acceleration runs.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Test
    fun sessionsListEverySeededSession() {
        app.openSessions()
        app.waitForText("Compare laps").assertIsDisplayed()
        for (car in listOf(SmokeFixtures.VideoCar, SmokeFixtures.PlainCar, SmokeFixtures.HillCar)) {
            app.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(car, substring = true))
            app.onNodeWithText(car, substring = true, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun everyAnalysisTabOfACircuitSession() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        // The summary: best lap, theoretical best and where time goes.
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("THEORETICAL").assertIsDisplayed()
        app.waitForText("WHERE TIME GOES").performScrollTo().assertIsDisplayed()
        // The whole session's G-forces, with their peaks.
        app.waitForText("G-FORCES · WHOLE SESSION").performScrollTo().assertIsDisplayed()
        app.waitForText("Peaks: braking", substring = true)

        app.openTab("Compare")
        app.waitForText("TIME DELTA").assertIsDisplayed()
        app.waitForText("vs best lap", substring = true)

        app.openTab("Map")
        app.waitForText("G-G · TRACTION CIRCLE").performScrollTo()
        app.waitForText("Time vs best").performScrollTo().performClick()
        app.waitForText("Time vs best: where this lap", substring = true)
        app.waitForText("Touch or drag along the line", substring = true).performScrollTo()
        app.onNode(hasText("Speed") and hasClickAction()).performScrollTo().performClick()

        app.openTab("Laps")
        app.waitForText("LAPS · TAP ONE TO COMPARE").assertIsDisplayed()
        // Tapping a lap compares it, which opens Compare.
        app.waitForText("Lap 2").performClick()
        app.waitForText("TIME DELTA")

        app.openTab("Corners")
        app.waitForText("THEORETICAL BEST BY CORNER").assertIsDisplayed()
        app.waitForText("Corner 1", substring = true)
        app.back()
        app.waitForText("Compare laps")
    }

    @Test
    fun cornerLapsWithVideoAndCompare() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs)
        app.openTab("Corners")
        app.waitForText("Every lap ›").performScrollTo().performClick()

        // Corner laps: every counted lap through the corner, fastest first, each with its video.
        app.waitForText("Every lap")
        app.waitForText("fastest through the corner first", substring = true, timeoutMs = LoadTimeoutMs)
        app.waitForText("TIME THROUGH THE CORNER", substring = true)
        app.waitForText("Play").performScrollTo().performClick()
        app.waitForText("Again").assertIsDisplayed()
        // Let the clip play a moment: the player decodes the session's video.
        Thread.sleep(PlayMs)
        app.waitForIdle()

        // Tick two laps and compare them in the lap review.
        val boxes = app.onAllNodes(isToggleable())
        boxes[0].performScrollTo().performClick()
        boxes[1].performScrollTo().performClick()
        app.waitForText("against lap", substring = true)
        app.onNode(hasText("Compare") and hasClickAction()).assertIsEnabled()
        // The lap review runs a frame loop, so the clock is advanced by hand while it shows.
        app.withManualClock {
            app.onNode(hasText("Compare") and hasClickAction()).performClick()
            app.pumpUntilText("Compare laps", timeoutMs = LoadTimeoutMs)
            app.pumpUntilText("Change laps", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
            // Back goes to the review's lap picker, then back again to Corner laps.
            app.pumpBack()
            app.pumpUntilText("Compare laps")
            app.pumpBack()
            app.pumpUntil(LoadTimeoutMs, "the lap review to close") {
                app.onAllNodesWithText("Compare laps").fetchSemanticsNodes().isEmpty()
            }
        }
        app.waitForText("TIME THROUGH THE CORNER", substring = true)

        // Then to the analysis as it was left.
        app.back()
        // The analysis is read again after the lap review, so this can take a while.
        app.waitForText("THEORETICAL BEST BY CORNER", timeoutMs = LoadTimeoutMs)
    }

    @Test
    fun optimalLapVideoPlanner() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs)
        // The session's phone video covers every part of the theoretical best, so it can be made.
        app.waitForText("Every part on video", substring = true, timeoutMs = LoadTimeoutMs).performScrollTo()
        app.waitFor(hasText("Optimal lap video") and hasClickAction() and isEnabled()).performScrollTo().performClick()

        // The planner: each part with its lap; Cancel closes it without making the video.
        app.waitForText("The theoretical best", substring = true, timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitFor(hasText("Export") and hasClickAction()).assertIsEnabled()
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("The theoretical best", substring = true)
        check(app.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()) { "an optimal lap video started" }
    }

    @Test
    fun coachPicksToggle() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs)
        // A corner's flag in Where time goes: pick it for the coach, then unpick it.
        val pick = hasContentDescription("Coach ", substring = true) and
            hasContentDescription("next time", substring = true)
        val picked = hasContentDescription("Coaching ", substring = true)
        fun isPicked() = app.onAllNodes(picked).fetchSemanticsNodes().isNotEmpty()
        // The analysis can still lay out again (the progress card arrives after it), and a tap during
        // that can miss the flag: it is tapped again only while nothing is picked after a while, so a
        // tap that landed is never undone.
        for (attempt in 1..PickTries) {
            app.waitFor(pick, timeoutMs = LoadTimeoutMs).performScrollTo().performClick()
            val landed = runCatching { app.waitNamed("a corner picked", PickWaitMs) { isPicked() } }.isSuccess
            if (landed || attempt == PickTries) break
        }
        app.waitFor(picked).performScrollTo().assertIsDisplayed()
        app.onAllNodes(picked)[0].performClick()
        app.waitNamed("the pick to go", LoadTimeoutMs) { app.onAllNodes(picked).fetchSemanticsNodes().isEmpty() }

        // The same pick from the focus card's chip, when the session has one.
        if (app.onAllNodesWithText("Coach this next time").fetchSemanticsNodes().isNotEmpty()) {
            app.onAllNodesWithText("Coach this next time")[0].performScrollTo().performClick()
            app.waitForText("✓ Coaching this next time").performScrollTo().performClick()
            app.waitUntilGone("✓ Coaching this next time")
        }
    }

    @Test
    fun pointToPointSessionWithAccelerationRuns() {
        app.openSessions()
        app.openSession(SmokeFixtures.HillCar)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        // Standing starts up the hill: 0–100 km/h and more, the best first and the others on a tap.
        app.waitForText("ACCELERATION").performScrollTo().assertIsDisplayed()
        app.waitForText("0–100 km/h").performScrollTo().assertIsDisplayed()
        app.waitForText("1/4 mile").performScrollTo()
        app.waitForText("1 more run").performScrollTo().performClick()
        app.waitForText("Every run")

        for (title in listOf("Compare", "Map", "Laps", "Corners")) app.openTab(title)
        app.openTab("Map")
        app.waitForText("Touch or drag along the line", substring = true)
        app.back()
        app.waitForText("Compare laps")
    }

    private companion object {
        const val PlayMs = 2_000L
        const val PickTries = 3

        /** A pick shows within milliseconds; this long without one means the tap missed. */
        const val PickWaitMs = 10_000L
    }
}
