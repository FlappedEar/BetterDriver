package com.flappedear.betterdriver.shell

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Turning the tablet recreates the activity and switches Sessions between the list beside the
 * analysis (sideways) and the analysis on its own (upright) (FBBD-323). What the analysis keeps in
 * its saved state (its tab, compared lap, Map view, scroll) and the list's own state survive the
 * turn both ways.
 */
@RunWith(RobolectricTestRunner::class)
// The Galaxy Tab's window: on Robolectric's default 320 dp the list leaves the analysis no width to tap.
@Config(application = Application::class, qualifiers = Devices.TabletLandscape)
class SessionsListDetailRotationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    /** The window's size class, read once per activity as a recreated activity reads its new size. */
    private var window = Sideways

    @Test
    fun analysisKeepsItsStateWhenTurnedUpright() = turn(from = Sideways, to = Upright)

    @Test
    fun analysisKeepsItsStateWhenTurnedSideways() = turn(from = Upright, to = Sideways)

    @Test
    fun listKeepsItsStateWhenTurnedBackSideways() {
        window = Sideways
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppScreen { Panes() } }
        compose.onNodeWithText("list 0").performClick()
        compose.onNodeWithText("list 1").assertExists()
        // Upright with a session open, the list is not shown; sideways again it is, as it was left.
        window = Upright
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("detail 0").assertExists()
        window = Sideways
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("list 1").assertExists()
    }

    private fun turn(from: LayoutSize, to: LayoutSize) {
        window = from
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppScreen { Panes() } }
        compose.onNodeWithText("detail 0").performClick()
        compose.onNodeWithText("detail 1").assertExists()
        window = to
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("detail 1").assertExists()
        // And back.
        window = from
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("detail 1").assertExists()
    }

    /** Sessions with session "a" picked: each pane counts its taps in its saved state. */
    @Composable
    private fun Panes() {
        val layout = remember { window }
        SessionsListDetail(
            layout = layout,
            shown = "a",
            onClose = {},
            detail = { _, _, _, _ -> Counter("detail") },
            list = { m -> Column(m) { Counter("list") } },
        )
    }

    @Composable
    private fun Counter(name: String) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { taps++ }) { Text("$name $taps") }
    }

    private companion object {
        /** The Galaxy Tab held sideways and upright. */
        val Sideways = LayoutSize(WidthClass.Expanded, HeightClass.Medium)
        val Upright = LayoutSize(WidthClass.Medium, HeightClass.Expanded)
    }
}
