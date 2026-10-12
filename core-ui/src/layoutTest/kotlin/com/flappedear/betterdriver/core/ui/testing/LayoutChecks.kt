package com.flappedear.betterdriver.core.ui.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Layout checks for the screen tests on the JVM (FBBD-40, FBBD-62, FBBD-65), shared by the modules
// whose test source sets add this directory. Robolectric lays a screen out at a device's size; the
// checks read the semantics tree, so they need no pixel goldens.

/** The devices the screens are checked on (FBBD-40), as Robolectric qualifiers. */
object Devices {
    /** Pixel 6, upright: 411 x 914 dp. */
    const val PhonePortrait = "w411dp-h914dp-port-xhdpi"

    /** Pixel 6 held sideways, as on the windscreen mount. */
    const val PhoneLandscape = "w914dp-h411dp-land-xhdpi"

    /** Galaxy Tab, upright: about 800 x 1280 dp. */
    const val TabletPortrait = "w800dp-h1280dp-port-mdpi"

    /** Galaxy Tab held sideways. */
    const val TabletLandscape = "w1280dp-h800dp-land-mdpi"
}

/**
 * Registers ComponentActivity with Robolectric before createComposeRule starts it, as
 * roborazzi-compose does for its own activity: a library's unit tests have no manifest entry for
 * it (that would need ui-test-manifest in the module's debug build). Put it outside the compose rule.
 */
class RegisterComponentActivity : ExternalResource() {
    override fun before() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}

/**
 * Lays the activity out edge to edge, as MainActivity does, before content is set: the content
 * gets the whole screen, and only what pads for the system bars (the app's Scaffold) leaves room
 * for them. The track screen hides the bars, so it gets the whole screen too. Without this,
 * Robolectric's activity fits its content between its status and navigation bars, which the app
 * never does.
 */
fun <A : ComponentActivity> AndroidComposeTestRule<*, A>.edgeToEdge() {
    runOnUiThread { activity.enableEdgeToEdge() }
}

/** The size class of the configured device, as the app works it out for its window. */
@Composable
fun deviceLayout(): LayoutSize {
    val c = LocalConfiguration.current
    return LayoutSize(WidthClass.of(c.screenWidthDp.toFloat()), HeightClass.of(c.screenHeightDp.toFloat()))
}

/** [content] in the app's theme (always dark) on its background, filling the window. */
@Composable
fun AppScreen(content: @Composable () -> Unit) {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/**
 * Saves a picture of the whole window to build/outputs/roborazzi/layout/[device]/[name].png, which
 * CI keeps as an artifact to look at. Always recorded, never compared: the checks below are what
 * pass or fail, so these need no goldens.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteractionsProvider.saveScreenshot(device: String, name: String) {
    onRoot().captureRoboImage(
        "build/outputs/roborazzi/layout/$device/$name.png",
        roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
    )
}

/**
 * Fails when the screen shown has a layout problem (FBBD-40), naming each one:
 * - something scrolls sideways, or sticks out of the window to the left or right;
 * - a text is cut off: clipped by its own box (not ellipsized on purpose), or by a parent outside a
 *   vertically scrolling list, or reaching below the window where nothing scrolls;
 * - two texts overlap.
 * Ellipsized text ("…") is allowed: it is how long names are meant to fit.
 */
fun SemanticsNodeInteractionsProvider.assertLayoutClean(screen: String) {
    val problems = layoutProblems()
    if (problems.isNotEmpty()) {
        val lines = problems.joinToString("\n") { "  - $it" }
        throw AssertionError("$screen: ${problems.size} layout problem(s)\n$lines")
    }
}
