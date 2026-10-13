package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.deviceLayout
import com.flappedear.betterdriver.core.ui.testing.edgeToEdge
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
 * A corner's passes (FBBD-93) on a phone and a tablet, upright and sideways: the list, every pass's
 * traces and the player, with video and without, nothing clipped, overlapping or out of the window;
 * two ticked go to the lap review side by side, the faster first; and one tap from the next-session
 * focus and the coaching history opens a corner's passes, with the summary still opening on the
 * focus and then the best lap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class CornerLapsLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun passesTracesAndPlayerLayOutCleanly() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { CornerLapsScreen(previewCornerLaps(), onCompare = { _, _ -> }) } }
        compose.waitForIdle()
        compose.onNodeWithTag(CornerTracesTag).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("SPEED · 76–140 KM/H")).assertCountEquals(1)
        // The Medium pass shows no numbers, and says why.
        compose.onNodeWithText("Medium confidence · a GPS gap in this corner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Brakes – · Min –").assertExists()
        compose.onNodeWithText("Brakes 66 m · Min 81 km/h · Best actual lap").assertExists()
        compose.saveScreenshot(device, "corner_laps")
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "corner laps on $device (FBBD-93):\n" + problems.joinToString("\n") }
    }

    @Test
    fun withoutVideoTheTracesShowWithAQuietNote() {
        compose.edgeToEdge()
        compose.setContent {
            AppScreen { CornerLapsScreen(previewCornerLaps(withVideo = false), onCompare = { _, _ -> }) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(NoVideoText).assertIsDisplayed()
        compose.onNodeWithTag(CornerTracesTag).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Play")).assertCountEquals(0)
        compose.saveScreenshot(device, "corner_laps_no_video")
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "corner laps without video on $device:\n" + problems.joinToString("\n") }
    }

    @Test
    fun twoTickedGoSideBySideTheFasterFirst() {
        var compared: Pair<Int, Int>? = null
        compose.edgeToEdge()
        compose.setContent {
            AppScreen { CornerLapsScreen(previewCornerLaps(), onCompare = { a, b -> compared = a to b }) }
        }
        val boxes = compose.onAllNodes(isToggleable())
        // Lap 2 (index 1), then lap 3 (index 2), the fastest through the corner.
        boxes[2].performScrollTo().performClick()
        boxes[0].performScrollTo().performClick()
        compose.onNodeWithText("Lap 3 against lap 2, videos side by side").assertIsDisplayed()
        compose.onNode(hasText("Compare") and hasClickAction()).assertIsEnabled().performClick()
        check(compared == (2 to 1)) { "compared $compared" }
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "corner laps, two ticked, on $device:\n" + problems.joinToString("\n") }
    }

    @Test
    fun aCornerOfAnotherRevisionSaysSo() {
        compose.setContent {
            AppScreen { CornerLapsScreen(previewCornerLaps().copy(notInSession = true), onCompare = { _, _ -> }) }
        }
        compose.onNodeWithText(NotInSessionText).assertIsDisplayed()
    }

    /**
     * One tap from the next-session focus opens its corner's passes (FBBD-93), and the summary
     * still opens on the focus, then the best lap.
     */
    @Test
    fun theFocusOpensItsCornersPassesInOneTap() {
        val opened = ArrayList<String>()
        compose.edgeToEdge()
        compose.setContent {
            AppScreen {
                ProvideCornerLaps({ opened += it }) {
                    val ui = PreviewUi.copy(map = PreviewMap)
                    AnalysisScreen(AnalysisState.Ready(ui), onCompare = {}, layout = deviceLayout())
                }
            }
        }
        compose.waitForIdle()
        val next = compose.onNodeWithTag(NextSessionTag).fetchSemanticsNode()
        val best = compose.onAllNodes(hasText("BEST ACTUAL LAP"))[0].fetchSemanticsNode()
        val nextBottom = next.positionInRoot.y + next.size.height
        check(nextBottom <= best.positionInRoot.y) { "next session above the best lap on $device" }
        compose.onAllNodes(hasText(EveryPassText))[0].performScrollTo().performClick()
        check(opened == listOf("s2")) { "opened $opened" }
        // Coaching history: tapping a line opens its corner.
        compose.onAllNodes(hasText("Learned") and hasClickAction())[0].performScrollTo().performClick()
        check(opened == listOf("s2", "s1")) { "opened $opened" }
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "summary with every pass on $device:\n" + problems.joinToString("\n") }
    }
}

@Config(qualifiers = Devices.PhonePortrait)
class CornerLapsLayoutPhonePortraitTest : CornerLapsLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class CornerLapsLayoutPhoneLandscapeTest : CornerLapsLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class CornerLapsLayoutTabletPortraitTest : CornerLapsLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class CornerLapsLayoutTabletLandscapeTest : CornerLapsLayoutTest("tablet-landscape")
