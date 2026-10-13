package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.feature.analysis.NotMeasuredMark
import com.flappedear.betterdriver.feature.analysis.ProvenanceLegendTag
import com.flappedear.betterdriver.videoexport.RealisticOptimalVideoLabel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Sessions and lap analysis on the emulator (FBBD-161): every analysis tab of a circuit session
 * with video, the map, Corners → Every lap → Corner laps (with its video and Compare), the coach
 * picks, the optimal lap video's planner (FBBD-163), a point-to-point session with its
 * acceleration runs, and the mark on every value that is not measured (FBBD-174).
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
        // The summary: best actual lap, realistic optimal, raw theoretical best (FBBD-178) and where time goes.
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("REALISTIC OPTIMAL").assertIsDisplayed()
        app.waitForText("RAW THEORETICAL BEST").assertIsDisplayed()
        // The composite lap times are calculated, never shown as measured: marked, with the legend (FBBD-174).
        // A lap time is drawn glyph by glyph (NumberText) and read as one content description.
        val theoretical = hasContentDescription(NotMeasuredMark, substring = true) and
            hasContentDescription(":", substring = true)
        app.waitFor(theoretical).assertIsDisplayed()
        legend().performScrollTo().assertIsDisplayed()
        app.waitForText("WHERE TIME GOES").performScrollTo().assertIsDisplayed()
        // The whole session's G-forces, with their peaks.
        app.waitForText("G-FORCES · WHOLE SESSION").performScrollTo().assertIsDisplayed()
        app.waitForText("Peaks: braking", substring = true)

        app.openTab("Compare")
        // The time delta is worked out by distance: its chart is marked, and the card has the legend.
        app.waitForText("${NotMeasuredMark}TIME DELTA").assertIsDisplayed()
        legend().performScrollTo().assertIsDisplayed()
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
        app.waitForText("TIME DELTA", substring = true)

        app.openTab("Corners")
        app.waitForText("RAW THEORETICAL BEST BY CORNER").assertIsDisplayed()
        app.waitForText("Corner 1", substring = true)
        // No brake pedal is recorded: braking points are inferred from the accelerometer, so they are
        // marked in the corner details, and each such corner says what the mark means. A corner card
        // that opens Every lap is one clickable node, so its values are looked up unmerged.
        val inferred = hasText(NotMeasuredMark, substring = true) and hasText(" m", substring = true)
        app.waitNamed("a marked corner value") {
            app.exists(inferred, useUnmergedTree = true)
        }
        app.onAllNodes(inferred, useUnmergedTree = true)[0].performScrollTo().assertIsDisplayed()
        legend().performScrollTo().assertIsDisplayed()
        app.back()
        app.waitForText("Compare laps")
    }

    @Test
    fun cornerLapsWithVideoAndCompare() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
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
                !app.showsText("Compare laps")
            }
        }
        app.waitForText("TIME THROUGH THE CORNER", substring = true)

        // Then to the analysis as it was left.
        app.back()
        // The analysis is read again after the lap review, so this can take a while.
        app.waitForText("RAW THEORETICAL BEST BY CORNER", timeoutMs = LoadTimeoutMs)
    }

    @Test
    fun optimalLapVideoPlanner() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        // The session's phone video covers every part of the realistic optimal, so it can be made.
        app.waitForText("Every part on video", substring = true, timeoutMs = LoadTimeoutMs).performScrollTo()
        app.waitFor(hasText(RealisticOptimalVideoLabel) and hasClickAction() and isEnabled())
            .performScrollTo()
            .performClick()

        // The planner: the realistic optimal and the raw theoretical best, each part with its lap; Cancel
        // closes it without making the video.
        app.waitForText("Composite lap video", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("Realistic optimal · ", substring = true).assertIsDisplayed()
        app.waitForText("Raw theoretical best · ", substring = true).assertIsDisplayed()
        app.waitFor(hasText("Export") and hasClickAction()).assertIsEnabled()
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("Composite lap video")
        check(!app.showsText("Cancel")) { "a composite lap video started" }
    }

    @Test
    fun coachPicksToggle() {
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        // A corner's flag in Where time goes: pick it for the coach, then unpick it.
        val pick = hasContentDescription("Coach ", substring = true) and
            hasContentDescription("next time", substring = true)
        val picked = hasContentDescription("Coaching ", substring = true)
        // The analysis can still lay out again after it shows (the progress and data quality cards
        // arrive from background loads), which moves the flag under a tap aimed at it: each flag and
        // chip is clicked through its click action, once (FBBD-208).
        app.waitFor(pick, timeoutMs = LoadTimeoutMs).performScrollTo().assertIsDisplayed()
        app.clickAction(pick)
        app.waitFor(picked).performScrollTo().assertIsDisplayed()
        app.clickAction(picked)
        app.waitNamed("the pick to go", LoadTimeoutMs) { !app.exists(picked) }

        // The same pick from the focus card's chip, when the session has one.
        val chip = hasText("Coach this next time") and hasClickAction()
        if (app.exists(chip)) {
            app.waitFor(chip).performScrollTo().assertIsDisplayed()
            app.clickAction(chip)
            val chipPicked = hasText("✓ Coaching this next time") and hasClickAction()
            app.waitFor(chipPicked).performScrollTo().assertIsDisplayed()
            app.clickAction(chipPicked)
            app.waitUntilGone("✓ Coaching this next time")
        }
    }

    @Test
    fun pointToPointSessionWithAccelerationRuns() {
        app.openSessions()
        app.openSession(SmokeFixtures.HillCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
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

    /**
     * The first "≈" legend line (FBBD-174), looked up unmerged: in a corner card that opens Every lap,
     * the merged tree shows one clickable node without its children's tags.
     */
    private fun legend(): SemanticsNodeInteraction {
        val tag = hasTestTag(ProvenanceLegendTag)
        app.waitNamed("the provenance legend") {
            app.exists(tag, useUnmergedTree = true)
        }
        return app.onAllNodes(tag, useUnmergedTree = true)[0]
    }

    private companion object {
        const val PlayMs = 2_000L
    }
}
