package com.flappedear.betterdriver.videoexport

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.HudWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The video overlay editor (FBBD-472, round 65): a switch takes a widget off the video, a widget
 * dragged on the picture moves, Reset puts it back; from Settings there is no session to add values from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = Devices.PhonePortrait)
class OverlayEditorTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private var choice by mutableStateOf(HudChoice.All)

    private fun show() {
        compose.setContent {
            AppScreen {
                OverlayEditorScreen(choice = choice, data = null, onChange = { choice = it }, onBack = {})
            }
        }
    }

    @Test
    fun aSwitchTakesAWidgetOffTheVideo() {
        show()
        compose.onNodeWithTag(OverlayPictureTag).assertIsDisplayed()
        compose.onNodeWithTag("$OverlayPlateTag-w-${HudWidget.Oil.name}").assertIsDisplayed()
        compose.onNodeWithTag("$OverlaySwitchTag-${HudWidget.Oil.name}").performScrollTo()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("$OverlaySwitchTag-${HudWidget.Oil.name}")))
            .performClick()
        compose.waitForIdle()
        assertFalse(choice.isShown(HudWidget.Oil))
        compose.onNodeWithTag("$OverlayPlateTag-w-${HudWidget.Oil.name}").assertDoesNotExist()
    }

    @Test
    fun aDraggedWidgetMovesAndResetPutsItBack() {
        show()
        compose.onNodeWithTag("$OverlayPlateTag-w-${HudWidget.HeartRate.name}").performTouchInput {
            // From the top right corner down a third of the picture's height, into free road.
            swipe(center, center.copy(y = center.y + height * 2.2f), durationMillis = 400)
        }
        compose.waitForIdle()
        assertTrue(HudWidget.HeartRate in choice.places)
        assertTrue(choice.places.getValue(HudWidget.HeartRate).y > 0.2f)
        compose.onNodeWithText("Reset to Tech HUD").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(HudChoice.All, choice)
    }

    @Test
    fun fromSettingsValuesComeFromASessionsVideoTab() {
        show()
        compose.onNodeWithText("From a session's Video tab", substring = true).performScrollTo().assertIsDisplayed()
    }
}
