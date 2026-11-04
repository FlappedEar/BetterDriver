package com.flappedear.betterdriver.videoexport

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watch video's layout (FBBD-459): sideways on a phone the 16:9 picture takes the screen's height
 * but one row of controls, as it took about a third with the title bar and two rows of controls
 * (Arek's screenshot, 2026-11-04). Upright it keeps the title bar and the controls under the picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchLayoutTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private fun show() {
        compose.setContent {
            AppScreen {
                WatchLayout(
                    title = "sob. 29 sie, 16:06",
                    onBack = {},
                    picture = { m ->
                        Box(m.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(16f / 9f).testTag(Picture))
                        }
                    },
                    transport = { leading ->
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            leading?.invoke()
                            Text("Play")
                        }
                    },
                )
            }
        }
    }

    @Test
    @Config(qualifiers = Devices.PhoneLandscape)
    fun sidewaysThePictureTakesTheHeightButOneRow() {
        show()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(Picture).getUnclippedBoundsInRoot()
        val pictureHeight = (picture.bottom - picture.top).value
        val screenHeight = (screen.bottom - screen.top).value
        assertTrue("picture $pictureHeight of $screenHeight dp", pictureHeight / screenHeight >= MinWideShare)
        // No title bar; back is in the controls' row.
        compose.onNodeWithText("Watch video").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithText("Play").assertExists()
        compose.saveScreenshot("phone-landscape", "watch-video")
    }

    @Test
    @Config(qualifiers = Devices.PhonePortrait)
    fun uprightTheTitleBarAndControlsStay() {
        show()
        compose.onNodeWithText("Watch video").assertExists()
        compose.onNodeWithText("sob. 29 sie, 16:06").assertExists()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(Picture).getUnclippedBoundsInRoot()
        // Full width, less the screen's 16 dp margins.
        val pictureWidth = (picture.right - picture.left).value
        val screenWidth = (screen.right - screen.left).value
        assertTrue("picture $pictureWidth of $screenWidth dp", pictureWidth >= screenWidth - 33f)
        compose.onNodeWithText("Play").assertExists()
    }

    private companion object {
        const val Picture = "picture"

        /** On a 411 dp high window, all but the 48 dp row: about 0.88; it was about 0.4 before. */
        const val MinWideShare = 0.85f
    }
}
