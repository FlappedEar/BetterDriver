package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.edgeToEdge
import com.flappedear.betterdriver.core.ui.testing.deviceLayout
import com.flappedear.betterdriver.core.ui.testing.layoutProblems
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A session's analysis on a phone and a tablet, upright and sideways (FBBD-40), every tab with
 * the example analysis: no text clipped, overlapping or out of the window, nothing scrolling
 * sideways. The open tab survives a rotation. Pictures go to build/outputs/roborazzi/layout/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class AnalysisLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun everyTabLaysOutCleanly() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        val problems = ArrayList<String>()
        AnalysisTab.entries.forEach { t ->
            tab(t).performScrollTo().performClick()
            compose.waitForIdle()
            // Roborazzi keeps names ending in "_compare" for itself.
            compose.saveScreenshot(device, "analysis_${t.name.lowercase()}_tab")
            problems += compose.layoutProblems().map { "${t.title}: $it" }
        }
        check(problems.isEmpty()) { "session analysis on $device (FBBD-40):\n" + problems.joinToString("\n") }
    }

    /**
     * What to work on next (FBBD-20) is the first thing on the summary, above the best lap: the
     * primary focus, and its secondary ones.
     */
    @Test
    fun nextSessionComesFirst() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        val next = compose.onNodeWithTag(NextSessionTag).fetchSemanticsNode().boundsInRoot
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode().boundsInRoot
        check(next.bottom <= best.top) { "next session at $next, best lap at $best on $device" }
        compose.onNodeWithTag(PrimaryFocusTag).assertIsDisplayed().assert(hasText("Corners 2–3"))
    }

    /**
     * What came of the last focus (FBBD-180) follows the lap times, so the next focus and the best
     * lap stay first. Measured unclipped: on a phone on its side the history is below the fold.
     */
    @Test
    fun coachingHistoryAfterTheLapTimes() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        fun top(node: SemanticsNode) = node.positionInRoot.y
        val next = compose.onNodeWithTag(NextSessionTag).fetchSemanticsNode()
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode()
        val history = compose.onNodeWithTag(CoachingHistoryTag).fetchSemanticsNode()
        val nextBottom = top(next) + next.size.height
        check(nextBottom <= top(best) && top(best) < top(history)) {
            "next session at ${top(next)}..$nextBottom, best lap at ${top(best)}, history at ${top(history)} on $device"
        }
        compose.onNode(hasText("Learned") and hasAnyAncestor(hasTestTag(CoachingHistoryTag))).assertExists()
    }

    /**
     * The way to the progression at the track (FBBD-181) sits in the card against earlier sessions,
     * under the best lap, so the summary still opens on the next focus and the best lap.
     */
    @Test
    fun progressionLinkUnderTheBestLap() {
        var opened = false
        compose.edgeToEdge()
        compose.setContent {
            AppScreen {
                AnalysisScreen(
                    AnalysisState.Ready(PreviewUi.copy(map = PreviewMap)),
                    onCompare = {},
                    layout = deviceLayout(),
                    onProgression = { opened = true },
                )
            }
        }
        compose.waitForIdle()
        val next = compose.onNodeWithTag(NextSessionTag).fetchSemanticsNode()
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode()
        val link = compose.onNodeWithTag(ProgressionLinkTag).fetchSemanticsNode()
        val nextBottom = next.positionInRoot.y + next.size.height
        check(nextBottom <= best.positionInRoot.y && best.positionInRoot.y < link.positionInRoot.y) {
            "next at ${next.positionInRoot.y}, best lap at ${best.positionInRoot.y}, link at ${link.positionInRoot.y}"
        }
        compose.onNodeWithTag(ProgressionLinkTag).performScrollTo().performClick()
        check(opened) { "the progression did not open" }
    }

    /**
     * Consistency on the summary is the typical (median) lap and the spread of the middle half
     * (FBBD-175), in the card against earlier sessions, under the next focus and the best lap.
     */
    @Test
    fun typicalLapAndSpreadUnderTheBestLap() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode()
        val typical = compose.onNodeWithTag(TypicalLapTag).assert(hasText("Typical lap 1:21.74")).fetchSemanticsNode()
        check(best.positionInRoot.y < typical.positionInRoot.y) { "typical lap above the best lap on $device" }
        compose.onAllNodes(hasText("Lap spread 0.42 s")).assertCountEquals(1)
    }

    /**
     * The Car tab (FBBD-176, UI-157 B) shows the pedals by lap, the temperatures by lap with the one
     * not recorded named, and the heart rate chart; a lap without pedals says so instead of a bar.
     */
    @Test
    fun carTabShowsPedalsTemperaturesAndHeartRate() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        tab(AnalysisTab.Car).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PedalCardTag).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Not recorded")).assertExists()
        compose.onNodeWithTag(TemperatureCardTag).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Not recorded: intake")).assertExists()
        compose.onNodeWithTag(HeartRateChartTag).performScrollTo().assertIsDisplayed()
    }

    /**
     * The Compare tab shows what each lap's car was doing along the lap and how long each coasted
     * (FBBD-175), marked as inferred; the Corners tab shows each corner's coasting, marked too.
     */
    @Test
    fun drivingStatesOnCompareAndCoastingOnCorners() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        tab(AnalysisTab.Compare).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DrivingStatesTag).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Coasting: lap 2 ≈1.32 s · lap 3 ≈2.05 s")).performScrollTo().assertIsDisplayed()
        tab(AnalysisTab.Corners).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(CoastingLabel))[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("≈0.12 s"))[0].assertExists()
    }

    /** The coach line shows only where the spoken coach will work on the focus first. */
    @Test
    fun coachLineOnlyWhereTheCoachWorksOnTheFocus() {
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        compose.onAllNodes(hasText(CoachFocusFirstText)).assertCountEquals(0)
    }

    @Test
    fun coachLineWhereTheCoachWorksOnTheFocus() {
        compose.setContent { AppScreen { Analysis(coachFocusFirst = true) } }
        compose.waitForIdle()
        compose.onAllNodes(hasText(CoachFocusFirstText)).assertCountEquals(1)
    }

    /**
     * Heart rate (FBBD-225, UI-107 A): a card under the best lap, and each lap's average and highest
     * in the laps. A lap without heart rate has an empty cell, never 0 (UI-109).
     */
    @Test
    fun heartRateUnderTheBestLapAndInTheLaps() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode()
        val hr = compose.onNode(hasText("HEART RATE · POLAR H10 1234")).fetchSemanticsNode()
        check(best.positionInRoot.y < hr.positionInRoot.y) {
            "best lap at ${best.positionInRoot.y}, heart rate at ${hr.positionInRoot.y} on $device"
        }
        tab(AnalysisTab.Laps).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("BPM AVG · MAX")).assertExists()
        compose.onNode(hasText("138 · 158")).assertExists()
        compose.onAllNodes(hasText("0 · 0")).assertCountEquals(0)
    }

    /** A session without heart rate shows none: no card, no column (UI-109). */
    @Test
    fun noHeartRateWithoutIt() {
        val laps = PreviewUi.laps.map { it.copy(heartRate = null) }
        val ui = PreviewUi.copy(map = PreviewMap, heartRate = null, laps = laps)
        compose.setContent {
            AppScreen { AnalysisScreen(AnalysisState.Ready(ui), onCompare = {}, layout = deviceLayout()) }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText("HEART RATE", substring = true)).assertCountEquals(0)
        tab(AnalysisTab.Laps).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("BPM", substring = true)).assertCountEquals(0)
    }

    /** Rotating the device recreates the activity: the open tab stays open. */
    @Test
    fun openTabSurvivesRotation() {
        compose.edgeToEdge()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppScreen { Analysis() } }
        tab(AnalysisTab.Laps).performClick()
        tab(AnalysisTab.Laps).assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        tab(AnalysisTab.Laps).assertIsSelected()
    }

    private fun tab(t: AnalysisTab) = compose.onNode(hasText(t.title) and IsTab)

    private companion object {
        val IsTab = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    }
}

@Composable
private fun Analysis(coachFocusFirst: Boolean = false) {
    AnalysisScreen(
        AnalysisState.Ready(PreviewUi.copy(map = PreviewMap, coachFocusFirst = coachFocusFirst)),
        onCompare = {},
        layout = deviceLayout(),
    )
}

@Config(qualifiers = Devices.PhonePortrait)
class AnalysisLayoutPhonePortraitTest : AnalysisLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class AnalysisLayoutPhoneLandscapeTest : AnalysisLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class AnalysisLayoutTabletPortraitTest : AnalysisLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class AnalysisLayoutTabletLandscapeTest : AnalysisLayoutTest("tablet-landscape")
