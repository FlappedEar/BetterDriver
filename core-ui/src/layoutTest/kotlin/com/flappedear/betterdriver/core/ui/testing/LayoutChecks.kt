package com.flappedear.betterdriver.core.ui.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
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

/** What [assertLayoutClean] finds, one line each. */
fun SemanticsNodeInteractionsProvider.layoutProblems(): List<String> {
    val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val width = root.size.width.toFloat()
    val height = root.size.height.toFloat()
    val nodes = onAllNodes(AnyNode, useUnmergedTree = true).fetchSemanticsNodes()
    val problems = ArrayList<String>()
    for (node in nodes) {
        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { range ->
            if (range.maxValue() > Slack) problems += "${describe(node)} scrolls sideways by ${range.maxValue()} px"
        }
        if (node.size.width == 0 || node.size.height == 0) continue
        val left = node.positionInRoot.x
        val right = left + node.size.width
        if (left < -Slack || right > width + Slack) {
            problems += "${describe(node)} sticks out of the window: x $left to $right of $width px"
        }
        if (isText(node)) problems += textProblems(node, height)
    }
    problems += overlaps(nodes.filter(::isText))
    return problems
}

private fun textProblems(node: SemanticsNode, windowHeight: Float): List<String> {
    val problems = ArrayList<String>()
    val name = describe(node)
    layoutOf(node)?.let { layout ->
        if (layout.hasVisualOverflow && layout.layoutInput.overflow != TextOverflow.Ellipsis) {
            problems += "$name does not fit its box and is clipped"
        }
    }
    val bounds = node.boundsInRoot
    val scrolls = insideVerticalScroll(node)
    if (bounds.width < node.size.width - Slack) {
        problems += "$name is cut off sideways: ${bounds.width} of ${node.size.width} px shown"
    }
    if (!scrolls) {
        if (bounds.height < node.size.height - Slack) {
            problems += "$name is cut off: ${bounds.height} of ${node.size.height} px shown"
        }
        if (node.positionInRoot.y + node.size.height > windowHeight + Slack) {
            problems += "$name reaches below the window and nothing scrolls"
        }
    }
    return problems
}

/** Text nodes that overlap, as pairs; a node and its own descendants are not compared. */
private fun overlaps(texts: List<SemanticsNode>): List<String> {
    val problems = ArrayList<String>()
    for (i in texts.indices) {
        for (j in i + 1 until texts.size) {
            val a = texts[i]
            val b = texts[j]
            if (related(a, b)) continue
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            if (overlap.width > Slack && overlap.height > Slack) {
                problems += "${describe(a)} and ${describe(b)} overlap by ${size(overlap)}"
            }
        }
    }
    return problems
}

private fun related(a: SemanticsNode, b: SemanticsNode): Boolean = isAncestor(a, b) || isAncestor(b, a)

private fun isAncestor(ancestor: SemanticsNode, node: SemanticsNode): Boolean {
    var p = node.parent
    while (p != null) {
        if (p.id == ancestor.id) return true
        p = p.parent
    }
    return false
}

private fun insideVerticalScroll(node: SemanticsNode): Boolean {
    var p = node.parent
    while (p != null) {
        if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) return true
        p = p.parent
    }
    return false
}

/** A text: a Text's own node, or a node that reads a value as one (NumberText's content description). */
private fun isText(node: SemanticsNode): Boolean =
    node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true ||
        (node.children.isEmpty() && node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true)

/** The text's layout, from the node's GetTextLayoutResult action (Text has it; other nodes do not). */
fun layoutOf(node: SemanticsNode): TextLayoutResult? {
    val results = ArrayList<TextLayoutResult>()
    val got = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) == true
    return if (got) results.firstOrNull() else null
}

private fun describe(node: SemanticsNode): String {
    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        ?: node.config.getOrNull(SemanticsProperties.TestTag)
    return if (text != null) "\"$text\"" else "node ${node.id}"
}

private fun size(r: Rect) = "%.0f x %.0f px".format(r.width, r.height)

private val AnyNode = SemanticsMatcher("any node") { true }

/** Rounding of positions to whole pixels. */
private const val Slack = 1f
