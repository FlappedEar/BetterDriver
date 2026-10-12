package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
private fun Analysis() {
    AnalysisScreen(
        AnalysisState.Ready(PreviewUi.copy(map = PreviewMap)),
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
