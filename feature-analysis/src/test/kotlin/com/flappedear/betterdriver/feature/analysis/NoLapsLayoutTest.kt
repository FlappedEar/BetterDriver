package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * A session with no laps (a road drive) keeps the six tabs, as in the iOS app: the message and the
 * drive on Summary, the car on Car, every tab laid out cleanly on a phone and a tablet, upright and
 * sideways. A session that could not be read shows only its message and data quality. Pictures go to
 * build/outputs/roborazzi/layout/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class NoLapsLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun everyTabLaysOutCleanly() {
        show(RoadDrive)
        val problems = ArrayList<String>()
        AnalysisTab.entries.forEach { t ->
            tab(t).performClick()
            compose.waitForIdle()
            compose.saveScreenshot(device, "no_laps_${t.name.lowercase()}_tab")
            problems += compose.layoutProblems().map { "${t.title}: $it" }
        }
        check(problems.isEmpty()) { "no-laps analysis on $device:\n" + problems.joinToString("\n") }
    }

    @Test
    fun summaryShowsTheMessageAndTheDrive() {
        show(RoadDrive)
        tab(AnalysisTab.Summary).assertIsSelected()
        compose.onNode(hasText(RoadDrive.message)).assertIsDisplayed()
        compose.onNode(hasText("Tracks")).assertIsDisplayed()
        compose.onNodeWithTag(DriveMapTag).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tabsThatNeedLapsSaySo() {
        show(RoadDrive)
        listOf(
            AnalysisTab.Compare to NoCompare,
            AnalysisTab.Map to NoLine,
            AnalysisTab.Laps to NoTimedLaps,
            AnalysisTab.Corners to NoCorners,
        ).forEach { (t, words) ->
            tab(t).performClick()
            compose.waitForIdle()
            compose.onNode(hasText(words)).assertIsDisplayed()
        }
    }

    @Test
    fun carTabShowsTheWholeDrive() {
        show(RoadDrive)
        tab(AnalysisTab.Car).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PedalCardTag).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unreadableSessionIsOnlyItsMessageAndDataQuality() {
        show(AnalysisState.Unavailable("This session could not be read.", quality = PreviewQuality))
        compose.onNode(hasText("This session could not be read.")).assertIsDisplayed()
        compose.onAllNodes(hasText("Data quality", ignoreCase = true))[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodes(IsTab).assertCountEquals(0)
    }

    private fun show(state: AnalysisState) {
        compose.edgeToEdge()
        compose.setContent { AppScreen { AnalysisScreen(state, onCompare = {}, layout = deviceLayout()) } }
        compose.waitForIdle()
    }

    private fun tab(t: AnalysisTab) = compose.onNode(hasText(t.title) and IsTab)

    private companion object {
        val IsTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val RoadDrive = AnalysisState.Unavailable(
            "No laps: this session, a road drive for example, was not recorded at a track with a start/finish line.",
            action = "Tracks",
            acceleration = PreviewAcceleration,
            quality = PreviewQuality,
            car = PreviewCar,
            drive = PreviewDrive,
        )
    }
}

@Config(qualifiers = Devices.PhonePortrait)
class NoLapsLayoutPhonePortraitTest : NoLapsLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class NoLapsLayoutPhoneLandscapeTest : NoLapsLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class NoLapsLayoutTabletPortraitTest : NoLapsLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class NoLapsLayoutTabletLandscapeTest : NoLapsLayoutTest("tablet-landscape")
