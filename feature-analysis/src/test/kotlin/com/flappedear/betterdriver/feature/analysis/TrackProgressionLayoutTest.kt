package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
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
 * The progression at a track (FBBD-181) on a phone and a tablet, upright and sideways: by session,
 * track day and month, a corner opened, nothing clipped or overlapping; the laps first, then the
 * corners, the coaching and the sessions; the driver's choices reach the route.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TrackProgressionLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private val chosen = ArrayList<String>()

    private val actions = object : ProgressionActions {
        override fun grouping(grouping: ProgressionGrouping) {
            chosen += "grouping $grouping"
        }

        override fun period(period: ProgressionPeriod) {
            chosen += "period $period"
        }

        override fun toggle(sessionId: String) {
            chosen += "toggle $sessionId"
        }
    }

    @Composable
    private fun Progression() = TrackProgressionScreen(ProgressionState.Ready(previewProgressionUi()), actions)

    @Test
    fun everyGroupingLaysOutCleanly() {
        compose.edgeToEdge()
        val problems = ArrayList<String>()
        var grouping by mutableStateOf(ProgressionGrouping.Session)
        compose.setContent {
            AppScreen { TrackProgressionScreen(ProgressionState.Ready(previewProgressionUi(grouping)), actions) }
        }
        for (g in ProgressionGrouping.entries) {
            grouping = g
            compose.waitForIdle()
            compose.saveScreenshot(device, "progression_${g.name.lowercase()}")
            problems += compose.layoutProblems().map { "$g: $it" }
        }
        check(problems.isEmpty()) { "progression on $device (FBBD-181):\n" + problems.joinToString("\n") }
    }

    @Test
    fun aCornerOpensToItsMetricsCleanly() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Progression() } }
        compose.onAllNodes(hasText("Braking point")).assertCountEquals(0)
        compose.onNodeWithText("Corner 2").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Braking point").performScrollTo()
        compose.onNodeWithText("9 m later over 4 sessions").performScrollTo()
        compose.saveScreenshot(device, "progression_corner_open")
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "progression corner on $device:\n" + problems.joinToString("\n") }
    }

    @Test
    fun lapsThenCornersThenCoachingThenSessions() {
        compose.setContent { AppScreen { Progression() } }
        compose.waitForIdle()
        val tops = listOf(ProgressionLapsTag, ProgressionCornersTag, ProgressionCoachingTag, ProgressionPointsTag)
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        check(tops == tops.sorted()) { "cards out of order on $device: $tops" }
        compose.onNodeWithText("Imported · VBO lap-12.vbo").performScrollTo()
    }

    @Test
    fun choicesReachTheRoute() {
        compose.setContent { AppScreen { Progression() } }
        compose.onNodeWithText("Track day").performClick()
        compose.onNodeWithText("90 days").performClick()
        compose.onNodeWithText("Imported · VBO lap-12.vbo").performScrollTo().performClick()
        check(chosen == listOf("grouping TrackDay", "period Quarter", "toggle p2")) { "chosen: $chosen" }
    }
}

@Config(qualifiers = Devices.PhonePortrait)
class TrackProgressionLayoutPhonePortraitTest : TrackProgressionLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class TrackProgressionLayoutPhoneLandscapeTest : TrackProgressionLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class TrackProgressionLayoutTabletPortraitTest : TrackProgressionLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class TrackProgressionLayoutTabletLandscapeTest : TrackProgressionLayoutTest("tablet-landscape")
