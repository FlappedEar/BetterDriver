package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackColorsBlueOrange
import com.flappedear.betterdriver.core.ui.TrackColorsGreenRed
import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.DeltaDisplay
import com.flappedear.betterdriver.core.ui.component.LapTimes
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.ValueState
import com.flappedear.betterdriver.core.ui.component.ValueTile
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.layoutProblems
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.atan

/**
 * The track screen laid out on each device it runs on (it is locked to landscape), with every
 * state and with values changing (FBBD-40, FBBD-62, FBBD-65). Pictures of each state are saved to
 * build/outputs/roborazzi/layout/ for a look; the checks read the semantics tree.
 *
 * - [everyStateLaysOutCleanly]: no text clipped, overlapping or out of the window (FBBD-40).
 * - [slotsAndLabelsDoNotMoveWhenValuesChange]: from no lap yet to long lap times, a gain to a
 *   loss, one digit to three, every slot, label and value anchor stays where it was (FBBD-62).
 * - [valuesFitTheirSlots]: each value's tile is at least as wide as its content needs, so no digit
 *   is drawn outside it (FBBD-62).
 * - [glanceValuesAreLargeEnough]: delta, lap times and tiles are at least 16 arcminutes tall at
 *   70 cm (FBBD-65).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TrackScreenLayoutTest(private val device: String) {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private var state by mutableStateOf(ExampleTrackState)
    private var colors by mutableStateOf<TrackColors>(TrackColorsGreenRed)

    /** When set, the content is drawn at its natural width at these slot heights instead of the screen. */
    private var natural by mutableStateOf<Map<String, Dp>?>(null)

    private fun show() = compose.setContent {
        BetterDriverTheme(track = true, trackColors = colors) {
            val heights = natural
            if (heights == null) TrackScreenContent(state) else NaturalWidths(state, heights)
        }
    }

    private fun showState(s: TrackScreenState, palette: TrackColors = TrackColorsGreenRed) {
        state = s
        colors = palette
        compose.waitForIdle()
    }

    @Test
    fun everyStateLaysOutCleanly() {
        show()
        val problems = ArrayList<String>()
        States.forEach { (name, s, palette) ->
            showState(s, palette)
            compose.saveScreenshot(device, "track_$name")
            problems += compose.layoutProblems().map { "$name: $it" }
        }
        check(problems.isEmpty()) { "track screen on $device (FBBD-40):\n" + problems.joinToString("\n") }
    }

    @Test
    fun slotsAndLabelsDoNotMoveWhenValuesChange() {
        show()
        showState(Updates.first())
        val first = positions()
        val moved = ArrayList<String>()
        Updates.drop(1).forEachIndexed { i, s ->
            showState(s)
            positions().forEach { (what, at) ->
                val was = first.getValue(what)
                if (!same(was, at)) moved += "update ${i + 1}: $what moved from $was to $at"
            }
        }
        check(moved.isEmpty()) { "track screen on $device (FBBD-62):\n" + moved.joinToString("\n") }
    }

    @Test
    fun valuesFitTheirSlots() {
        show()
        val problems = ArrayList<String>()
        (States.map { it.first to it.second } + Updates.mapIndexed { i, s -> "update_$i" to s }).forEach { (name, s) ->
            natural = null
            showState(s)
            val slots = GlanceSlots.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().size }
            natural = slots.mapValues { (_, size) -> with(compose.density) { size.height.toDp() } }
            compose.waitForIdle()
            slots.forEach { (tag, size) ->
                val needs = compose.onNodeWithTag(naturalTag(tag)).fetchSemanticsNode().size.width
                if (needs > size.width + Slack) {
                    problems += "$name: $tag needs $needs px, its slot is ${size.width} px"
                }
            }
        }
        natural = null
        check(problems.isEmpty()) {
            "values too wide for their slots on $device (FBBD-62):\n" + problems.joinToString("\n")
        }
    }

    @Test
    fun glanceValuesAreLargeEnough() {
        show()
        showState(ExampleTrackState)
        val small = ArrayList<String>()
        GlanceSlots.forEach { tag ->
            values(tag).forEach { node ->
                // NumberText's line height is its font size; capitals and digits are about 0.7 of it.
                val sizeDp = with(compose.density) { node.size.height.toDp() }.value
                val capMm = sizeDp * CapHeightEm * MmPerDp
                val arcMinutes = Math.toDegrees(atan(capMm / ViewingDistanceMm)) * ArcMinutesPerDegree
                if (arcMinutes < MinArcMinutes) {
                    small += "$tag \"${description(node)}\": %.1f dp, %.1f′ at %d cm".format(
                        sizeDp,
                        arcMinutes,
                        (ViewingDistanceMm / MmPerCm).toInt(),
                    )
                }
            }
        }
        check(small.isEmpty()) {
            "glance values under $MinArcMinutes′ on $device (FBBD-65):\n" + small.joinToString("\n")
        }
    }

    /** Where each slot, label and value anchor is now, by name. */
    private fun positions(): Map<String, Rect> {
        val at = LinkedHashMap<String, Rect>()
        (FixedSlots + GlanceSlots).forEach { tag ->
            at[tag] = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        Labels.forEach { label ->
            at["label $label"] = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        // Values: the delta and tiles start at a fixed place, lap times end at one; all keep their top.
        values(TrackSlots.Delta).forEachIndexed { i, n -> at["delta value $i"] = leftTop(n) }
        values(TrackSlots.Laps).forEachIndexed { i, n -> at["lap time $i"] = rightTop(n) }
        (0 until TileCount).forEach { t ->
            values(TrackSlots.tile(t)).forEachIndexed { i, n -> at["tile $t value $i"] = leftTop(n) }
        }
        return at
    }

    /** The NumberText values inside the slot tagged [tag], in order. */
    private fun values(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and HasDescription, useUnmergedTree = true)
            .fetchSemanticsNodes()

    private fun leftTop(n: SemanticsNode) = n.boundsInRoot.let { Rect(it.left, it.top, it.left, it.top) }

    private fun rightTop(n: SemanticsNode) = n.boundsInRoot.let { Rect(it.right, it.top, it.right, it.top) }

    private fun same(a: Rect, b: Rect) = abs(a.left - b.left) <= Slack && abs(a.top - b.top) <= Slack &&
        abs(a.right - b.right) <= Slack && abs(a.bottom - b.bottom) <= Slack

    private fun description(n: SemanticsNode) =
        n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")

    private companion object {
        val FixedSlots = listOf(TrackSlots.Top, TrackSlots.Coach, TrackSlots.Sectors)
        const val TileCount = 3
        val GlanceSlots = listOf(TrackSlots.Delta, TrackSlots.Laps) + (0 until TileCount).map { TrackSlots.tile(it) }
        val Labels = listOf("DELTA VS BEST", "PREDICTED", "LAST", "BEST", "SPEED", "G", "LAP")

        val HasDescription = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

        /** Pixels: positions are rounded to whole pixels. */
        const val Slack = 1f

        /** ISO 15008's minimum character height for in-vehicle displays; 20′ is recommended. */
        const val MinArcMinutes = 16.0

        /** Eye to a windscreen-mounted phone. */
        const val ViewingDistanceMm = 700.0
        const val MmPerCm = 10.0
        const val CapHeightEm = 0.7
        const val MmPerDp = 25.4 / 160
        const val ArcMinutesPerDegree = 60.0

        fun naturalTag(tag: String) = "natural-$tag"

        /** Each state the track screen shows, with the palette to show it in. */
        val States: List<Triple<String, TrackScreenState, TrackColors>> = listOf(
            Triple("lap_in_progress", ExampleTrackState, TrackColorsGreenRed),
            Triple("before_first_lap", beforeFirstLap(), TrackColorsGreenRed),
            Triple(
                "gnss_lost",
                ExampleTrackState.copy(
                    alert = TrackAlert(
                        "GNSS signal lost",
                        "Lap timing paused · video still recording",
                        AlertLevel.Critical,
                    ),
                ),
                TrackColorsGreenRed,
            ),
            Triple(
                "warnings",
                ExampleTrackState.copy(
                    deltaSeconds = 1.27,
                    alert = TrackAlert(
                        "Phone hot",
                        "Video quality lowered · data still recording",
                        AlertLevel.Warning,
                    ),
                    tiles = listOf(
                        TrackTile("Speed", "98", "km/h"),
                        TrackTile("Oil", "124", "°C", ValueState.Warning),
                        TrackTile("Water", "108", "°C", ValueState.Critical),
                    ),
                ),
                TrackColorsGreenRed,
            ),
            Triple(
                "blue_orange_four_sectors",
                ExampleTrackState.copy(
                    sectors = listOf(
                        SectorState("S1", -0.21),
                        SectorState("S2", 0.08),
                        SectorState("S3", -0.05),
                        SectorState("S4", null, current = true),
                    ),
                ),
                TrackColorsBlueOrange,
            ),
            // A long lap (Nordschleife) and the widest values a tile shows.
            Triple(
                "long_lap",
                ExampleTrackState.copy(
                    deltaSeconds = -12.34,
                    predictedMillis = 525_170,
                    lastMillis = 545_330,
                    bestMillis = 523_990,
                    tiles = listOf(
                        TrackTile("Speed", "251", "km/h"),
                        TrackTile("G", "1.85", "g"),
                        TrackTile("Lap", "23", ""),
                    ),
                ),
                TrackColorsGreenRed,
            ),
        )

        /** The values changing during a session, in one layout (same tiles, no alert). */
        val Updates: List<TrackScreenState> = listOf(
            beforeFirstLap().copy(tiles = tiles("0", "0.00", "1")),
            ExampleTrackState.copy(
                deltaSeconds = 0.08,
                predictedMillis = 59_990,
                lastMillis = null,
                bestMillis = null,
                tiles = tiles("98", "0.98", "1"),
            ),
            ExampleTrackState.copy(deltaSeconds = -1.27, tiles = tiles("142", "1.12", "7")),
            ExampleTrackState.copy(
                deltaSeconds = 12.34,
                predictedMillis = 525_170,
                lastMillis = 545_330,
                bestMillis = 523_990,
                tiles = tiles("251", "1.85", "23"),
            ),
            ExampleTrackState.copy(deltaSeconds = -0.34, tiles = tiles("9", "0.05", "99")),
        )

        fun tiles(speed: String, g: String, lap: String) =
            listOf(TrackTile("Speed", speed, "km/h"), TrackTile("G", g, "g"), TrackTile("Lap", lap, ""))

        fun beforeFirstLap() = ExampleTrackState.copy(
            deltaSeconds = null,
            predictedMillis = null,
            lastMillis = null,
            bestMillis = null,
            sectors = listOf(
                SectorState("S1", null, current = true),
                SectorState("S2", null),
                SectorState("S3", null),
            ),
            coachCorner = null,
            coachCue = null,
        )
    }
}

/** The glance slots' content at the slot heights in [heights] and as wide as it wants to be. */
@Composable
private fun NaturalWidths(s: TrackScreenState, heights: Map<String, Dp>) {
    Column {
        Natural(TrackSlots.Delta, heights) { m -> DeltaDisplay(s.deltaSeconds, m) }
        Natural(TrackSlots.Laps, heights) { m -> LapTimes(s.predictedMillis, s.lastMillis, s.bestMillis, m) }
        s.tiles.take(TrackSlotsTileCount).forEachIndexed { i, t ->
            Natural(TrackSlots.tile(i), heights) { m -> ValueTile(t.label, t.value, t.unit, m, t.state) }
        }
    }
}

@Composable
private fun Natural(tag: String, heights: Map<String, Dp>, content: @Composable (Modifier) -> Unit) {
    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
        content(Modifier.height(heights.getValue(tag)).testTag("natural-$tag"))
    }
}

private const val TrackSlotsTileCount = 3

/** System bars shown by a swipe on the phone: 340 dp tall. */
private const val ShortWindow = "w914dp-h340dp-land-xhdpi"

@Config(qualifiers = Devices.PhoneLandscape)
class TrackScreenLayoutPhoneTest : TrackScreenLayoutTest("phone-landscape")

@Config(qualifiers = ShortWindow)
class TrackScreenLayoutShortWindowTest : TrackScreenLayoutTest("phone-landscape-short")

@Config(qualifiers = Devices.TabletLandscape)
class TrackScreenLayoutTabletTest : TrackScreenLayoutTest("tablet-landscape")
