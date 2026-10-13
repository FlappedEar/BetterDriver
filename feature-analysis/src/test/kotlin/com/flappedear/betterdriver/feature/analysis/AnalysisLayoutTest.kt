package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
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
            tab(t).performClick()
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

    /** What came of the last focus (FBBD-180) sits right under the next one, above the best lap. */
    @Test
    fun coachingHistoryBesideTheFocus() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Analysis() } }
        compose.waitForIdle()
        val next = compose.onNodeWithTag(NextSessionTag).fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag(CoachingHistoryTag).fetchSemanticsNode().boundsInRoot
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode().boundsInRoot
        check(next.bottom <= history.top && history.bottom <= best.top) {
            "next session at $next, coaching history at $history, best lap at $best on $device"
        }
        compose.onNode(hasText("Learned") and hasAnyAncestor(hasTestTag(CoachingHistoryTag))).assertExists()
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
