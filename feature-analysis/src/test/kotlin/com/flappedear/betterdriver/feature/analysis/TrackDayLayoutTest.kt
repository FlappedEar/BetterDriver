package com.flappedear.betterdriver.feature.analysis

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
 * The track day page (FBBD-315, UI round 48) on a phone and a tablet, upright and sideways: the
 * numbers, the sessions chart, the proof, the corners and next time, nothing clipped or overlapping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TrackDayLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun theDayLaysOutCleanly() {
        compose.edgeToEdge()
        compose.setContent { AppScreen { TrackDayScreen(TrackDayState.Ready(PreviewTrackDayUi)) } }
        compose.waitForIdle()
        compose.saveScreenshot(device, "track_day")
        val problems = compose.layoutProblems()
        check(problems.isEmpty()) { "track day on $device (FBBD-315):\n" + problems.joinToString("\n") }
        for (tag in listOf(
            TrackDayNumbersTag, TrackDaySessionsTag, TrackDayProofTag, TrackDayCornersTag, TrackDayNextTag,
        )) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }
}

@Config(qualifiers = Devices.PhonePortrait)
class TrackDayLayoutPhonePortraitTest : TrackDayLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class TrackDayLayoutPhoneLandscapeTest : TrackDayLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class TrackDayLayoutTabletPortraitTest : TrackDayLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class TrackDayLayoutTabletLandscapeTest : TrackDayLayoutTest("tablet-landscape")
