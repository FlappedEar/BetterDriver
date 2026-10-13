package com.flappedear.betterdriver.sessionanalysis

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Corner laps route opened on a corner (as from the progression, FBBD-93) for a session whose
 * analysis could not be made: it shows why, as the analysis screen does, instead of loading forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CornerLapsRouteTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun `a corner of a session whose analysis failed shows the analysis's unavailable state`() {
        compose.setContent {
            MaterialTheme {
                CornerLapsRoute(
                    nav = rememberNavController(),
                    sessionId = "s1",
                    source = CornerSource(null, null, null, failed = true),
                    initialCorner = "c1",
                ) { Text(Unavailable) }
            }
        }
        compose.onNodeWithText(Unavailable).assertExists()
    }

    private companion object {
        const val Unavailable = "This session could not be read."
    }
}
