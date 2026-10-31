package com.flappedear.betterdriver.shell

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.sessionanalysis.AnalysisTopBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a tablet the session list beside a session can be hidden and shown again from the analysis's
 * bar (Arek 2026-10-31: the list was stuck beside the session). Hidden, the analysis takes the
 * whole width; the list comes back as it was left, and closing the session brings it back too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = Devices.TabletLandscape)
class SessionsListHideTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private var shown by mutableStateOf<String?>("a")
    private var detailWidth = 0f

    @Test
    fun theListHidesAndComesBackAsItWasLeft() {
        compose.setContent { AppScreen { Panes(Sideways) } }
        compose.onNodeWithText("list 0").performClick()
        val beside = detailWidth
        compose.onNodeWithContentDescription(HideSessionList).performClick()
        compose.onNodeWithText("list 1").assertDoesNotExist()
        compose.onNodeWithText("detail").assertExists()
        assert(detailWidth > beside) { "hidden, the analysis is no wider: $detailWidth vs $beside" }
        compose.onNodeWithContentDescription(ShowSessionList).performClick()
        compose.onNodeWithText("list 1").assertExists()
        compose.onNodeWithContentDescription(HideSessionList).assertExists()
    }

    @Test
    fun closingTheSessionShowsTheListAgain() {
        compose.setContent { AppScreen { Panes(Sideways) } }
        compose.onNodeWithContentDescription(HideSessionList).performClick()
        compose.onNodeWithText("list 0").assertDoesNotExist()
        shown = null
        compose.onNodeWithText("list 0").assertExists()
        // The next session opens beside the list.
        shown = "b"
        compose.onNodeWithText("list 0").assertExists()
        compose.onNodeWithContentDescription(HideSessionList).assertExists()
    }

    @Test
    fun noToggleOnATabletHeldUpright() {
        var toggle: SessionListToggle? = SessionListToggle(false) {}
        compose.setContent {
            AppScreen {
                SessionsListDetail(
                    layout = Upright,
                    shown = "a",
                    onClose = {},
                    detail = { _, _, _, t -> toggle = t },
                    list = { m -> Column(m) { Text("list") } },
                )
            }
        }
        compose.waitForIdle()
        assertNull(toggle)
        // Upright with a session open, the session is on its own, with its Back button.
        assertEquals(0, compose.onAllNodes(hasText("list")).fetchSemanticsNodes().size)
    }

    @Composable
    private fun Panes(layout: LayoutSize) {
        SessionsListDetail(
            layout = layout,
            shown = shown,
            onClose = { shown = null },
            detail = { _, pane, onBack, listToggle ->
                Column {
                    AnalysisTopBar("detail", onBack = onBack, listToggle = listToggle, onProgression = null, onCoachReview = {})
                    BoxWithConstraints {
                        detailWidth = maxWidth.value
                    }
                }
            },
            list = { m -> Column(m) { Counter("list") } },
        )
    }

    @Composable
    private fun Counter(name: String) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { taps++ }) { Text("$name $taps") }
    }

    private companion object {
        val Sideways = LayoutSize(WidthClass.Expanded, HeightClass.Medium)
        val Upright = LayoutSize(WidthClass.Medium, HeightClass.Expanded)
    }
}
