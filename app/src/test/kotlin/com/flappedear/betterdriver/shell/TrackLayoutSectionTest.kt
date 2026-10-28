package com.flappedear.betterdriver.shell

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.feature.track.TrackSlots
import com.flappedear.betterdriver.recording.layoutTrackState
import com.flappedear.betterdriver.recording.settingsTileChoices
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.TrackScreens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Track screen's layout (FBBD-345): every car is listed to pick whose layout is drawn,
 * a tap on a tile of either screen opens its list, and a pick goes to the setup with its page and slot. A tablet window: see NewTrackRotationTest for why
 * a dialog test runs in one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = Devices.TabletPortrait)
class TrackLayoutSectionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private val chosen = ArrayList<Triple<Int, Int, String?>>()
    private var resets = 0
    private val picked = ArrayList<String>()

    private fun show() {
        val screens = TrackScreens(main = listOf("speed", "g", null), second = listOf("heart_rate"))
        compose.setContent {
            AppScreen {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackLayoutSection(
                        layoutTrackState(screens, receiverHasG = true, coachOn = true),
                        choices = { settingsTileChoices(true, "RaceBox Micro") },
                        onChoose = { page, slot, id -> chosen += Triple(page, slot, id) },
                        onReset = { resets++ },
                        cars = listOf(CarProfile("frs", "FR-S"), CarProfile("corolla", "Corolla")),
                        carId = "frs",
                        nextCarId = "frs",
                        onPickCar = { picked += it },
                    )
                }
            }
        }
    }

    @Test
    fun aTappedMainTileOpensItsListAndThePickIsSaved() {
        show()
        compose.onNodeWithTag(TrackSlots.tile(2)).performClick()
        compose.onNodeWithTag(TrackSlots.Picker).assertIsDisplayed()
        compose.onNodeWithText("Tile 3 · screen 1").assertIsDisplayed()
        compose.onNodeWithText("Coolant temp").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(0, 2, "obd_coolant_temp")), chosen)
        compose.onNodeWithTag(TrackSlots.Picker).assertDoesNotExist()
    }

    @Test
    fun aSecondScreenTileCanBeEmptied() {
        show()
        compose.onNodeWithTag(TrackSlots.secondTile(0)).performScrollTo().performClick()
        compose.onNodeWithText("Tile 1 · screen 2").assertIsDisplayed()
        compose.onNodeWithText("Empty").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(1, 0, null)), chosen)
    }

    @Test
    fun everyCarIsListedAndOneIsPicked() {
        show()
        compose.onNodeWithText("Next session").assertIsDisplayed()
        compose.onNodeWithText("Corolla").performClick()
        assertEquals(listOf("corolla"), picked)
    }

    @Test
    fun resetPutsTheDefaultsBack() {
        show()
        compose.onNodeWithText("Reset tiles to default").performScrollTo().performClick()
        assertEquals(1, resets)
    }
}
