package com.flappedear.betterdriver.feature.track.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.edgeToEdge
import com.flappedear.betterdriver.core.ui.testing.layoutProblems
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.SegmentType
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.corrected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Correcting a track's corners and straights in the track editor (FBBD-179), on a phone and a
 * tablet: the corners are listed under the lines, each correction is handed to the caller to store
 * the moment it is made and shown as stored, and nothing is clipped, overlapping or out of the window.
 * Pictures are saved to build/outputs/roborazzi/layout/ for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class CornerEditorTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val folder = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(folder).around(RegisterComponentActivity()).around(compose)

    private val back = ImageVector.Builder("back", 24.dp, 24.dp, 24f, 24f).build()

    /** The corrections handed to the caller, in order. */
    private val made = ArrayList<SegmentEdit>()

    /** The track as the caller stores it. */
    private var stored = OvalTrack

    private lateinit var state: TrackEditorState

    private fun show() {
        state = TrackEditorState(stored, emptyList())
        val tiles = TileLoader(folder.newFolder("tiles"), "BetterDriver test")
        compose.edgeToEdge()
        compose.setContent {
            AppScreen {
                TrackEditorScreen(
                    state = state,
                    sessions = emptyList(),
                    tiles = tiles,
                    backIcon = back,
                    onSave = {},
                    onBack = {},
                    onCorrect = { edit ->
                        made += edit
                        // As the app stores it: applied to the stored corners.
                        stored = stored.copy(identity = stored.identity?.corrected(edit))
                        state.correctionStored(stored, applied = true)
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private val problems = ArrayList<String>()

    private fun check(name: String) {
        compose.waitForIdle()
        compose.saveScreenshot(device, "track_editor_$name")
        problems += compose.layoutProblems().map { "$name: $it" }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    /** Picks [id] in the list, to correct it. */
    private fun pick(id: String) = compose.onNodeWithTag(SegmentRowTag + id).performScrollTo().performClick()

    /** The bar's Done, back to the list; the bar does not scroll. */
    private fun done() = compose.onNodeWithText("Done").performClick()

    private fun assertLayoutClean() =
        assertTrue("track editor on $device (FBBD-179):\n" + problems.joinToString("\n"), problems.isEmpty())

    @Test
    fun theCornersAreListedAndOneIsPickedToCorrect() {
        show()
        compose.onNodeWithText("Corners").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Found from your laps. Tap one here or on the map to correct it.").assertExists()
        check("corners")
        pick("c1")
        assertEquals("c1", state.segmentId)
        compose.onNodeWithText("Corner 1").assertIsDisplayed()
        check("corner_selected")
        done()
        assertNull(state.segmentId)
        compose.onNodeWithTag(SegmentRowTag + "c1").assertExists()
        assertLayoutClean()
    }

    @Test
    fun aCornerIsSplitAndCalledAChicane() {
        show()
        pick("c1")
        tap("Split")
        assertEquals(SegmentEdit.Split::class, made.last()::class)
        assertEquals(listOf("s1", "c1", "c3", "s2", "c2"), state.identity!!.segments.map { it.id })
        tap("Chicane")
        assertEquals(SegmentEdit.ChangeType("c1", SegmentType.Chicane), made.last())
        compose.onNodeWithText("Chicane 1").assertIsDisplayed()
        check("corner_split")
        assertLayoutClean()
    }

    @Test
    fun aCornerIsNamed() {
        show()
        pick("c1")
        tap("Rename")
        compose.onNode(hasSetTextAction()).performTextReplacement("Parabolica")
        check("corner_naming")
        tap("Save")
        assertEquals(SegmentEdit.Rename("c1", "Parabolica"), made.last())
        compose.onNodeWithText("Parabolica").assertIsDisplayed()
        check("corner_named")
        done()
        compose.onNodeWithText("Corrected by you. Every session here is analysed with these.").assertExists()
        compose.onNodeWithText("Edited").assertExists()
        check("corners_corrected")
        assertLayoutClean()
    }

    @Test
    fun cornersAreMergedAndStraightsRemovedDownToTwo() {
        stored = OvalTrack.copy(identity = OvalIdentity.corrected(SegmentEdit.Split("c1", splitAt)))
        show()
        pick("c1")
        tap("Merge with next")
        assertEquals(SegmentEdit.Merge("c1"), made.last())
        assertEquals(listOf("s1", "c1", "s2", "c2"), state.identity!!.segments.map { it.id })
        done()
        pick("s2")
        tap("Remove")
        assertEquals(SegmentEdit.Remove("s2"), made.last())
        // Two segments are the fewest a track has: nothing more can be removed or merged.
        assertEquals(2, state.identity!!.segments.size)
        assertNull(state.selectedSegment)
        pick("c1")
        compose.onNodeWithText("Remove").assertIsNotEnabled()
        compose.onNodeWithText("Merge with next").assertIsNotEnabled()
        check("corners_fewest")
        done()
        assertLayoutClean()
    }

    @Test
    fun restoreAutomaticBringsBackTheCornersFoundFromTheLaps() {
        stored = OvalTrack.copy(
            identity = OvalIdentity.corrected(SegmentEdit.Split("c1", splitAt))!!
                .corrected(SegmentEdit.Rename("c1", "Parabolica")),
        )
        show()
        compose.onNodeWithText("Corrected by you. Every session here is analysed with these.").assertExists()
        tap("Restore automatic")
        // The question is a dialog (a window of its own): the layout is checked once it is answered.
        compose.onNodeWithText("Restore automatic corners?").assertExists()
        compose.onNodeWithText("Restore").performClick()
        check("restored")
        assertEquals(SegmentEdit.RestoreAutomatic, made.last())
        assertEquals(OvalIdentity.segments, state.identity!!.segments)
        compose.onNodeWithText("Found from your laps. Tap one here or on the map to correct it.").assertExists()
        assertLayoutClean()
    }

    @Test
    fun aTapOnTheMapPicksTheCornerThereAndItsEndsDragAlongTheTrack() {
        show()
        val identity = OvalIdentity
        val c1 = identity.segment("c1")!!
        // A tap a few metres off the first bend picks it; one far from the track picks nothing.
        state.onTap(identity.pointAt((c1.startMeters + c1.endMeters) / 2))
        assertEquals("c1", state.segmentId)
        state.onTap(GeoPoint(OvalCenter.lat + 0.01, OvalCenter.lon))
        assertNull(state.segmentId)
        state.onTap(identity.pointAt(c1.startMeters + 30.0))
        assertEquals("c1", state.segmentId)
        compose.waitForIdle()
        assertEquals(2, state.segmentEnds().size)

        // Its end dragged 40 m on, along the track: a boundary move, kept short of the corner after.
        state.dragBoundary(SegmentEnd.End, identity.pointAt(c1.endMeters + 40.0))
        assertNotNull(state.dragged)
        val (end, at) = state.dragged!!
        assertEquals(SegmentEnd.End, end)
        assertTrue("$at", abs(at - (c1.endMeters + 40.0)) < 2.0)
        val edit = state.endDrag() as SegmentEdit.MoveBoundary
        assertEquals("s2", edit.id)
        assertNotNull(identity.corrected(edit))
        state.correctionStored(OvalTrack.copy(identity = identity.corrected(edit)), applied = true)
        assertEquals(c1.endMeters + 40.0, state.identity!!.segment("c1")!!.endMeters, 2.0)

        // Dragged past the end of the straight after it: it stops 10 m short.
        val s2 = state.identity!!.segment("s2")!!
        state.dragBoundary(SegmentEnd.End, state.identity!!.pointAt(s2.endMeters + 50.0))
        assertEquals(s2.endMeters - 10.0, state.dragged!!.second, 1.0)
        assertNotNull(state.endDrag())
        // A correction the stored corners no longer allow: the stored ones are shown, with a word why.
        state.correctionStored(OvalTrack, applied = false)
        assertEquals(OvalIdentity, state.identity)
        assertNotNull(state.correctionNote)
    }

    @Test
    fun aTrackNotSavedYetShowsItsCornersOnly() {
        state = TrackEditorState(OvalTrack, emptyList())
        val tiles = TileLoader(folder.newFolder("tiles"), "BetterDriver test")
        compose.edgeToEdge()
        compose.setContent {
            AppScreen { TrackEditorScreen(state, emptyList(), tiles, back, onSave = {}, onBack = {}) }
        }
        compose.onNodeWithText("Save the track to correct its corners.").performScrollTo().assertIsDisplayed()
        state.onTap(OvalIdentity.pointAt(10.0))
        compose.waitForIdle()
        assertNull(state.segmentId)
        check("corners_unsaved")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        val OvalCenter = GeoPoint(52.0, 21.0)
        private val frame = LocalFrame(OvalCenter)
        private const val Radius = 100.0
        private const val Straight = 300.0

        /** The middle of the first bend, where Split cuts it. */
        val splitAt = Straight + PI * Radius / 2

        /** A 1.2 km oval: the bottom straight east, a bend, the top straight west, a bend; every 2 m. */
        private val path: List<Xy> = run {
            val bend = PI * Radius
            val length = 2 * Straight + 2 * bend
            (0 until (length / 2).toInt()).map { i -> ovalAt(i * 2.0, bend) }
        }

        private fun ovalAt(s: Double, bend: Double): Xy {
            val half = Straight / 2
            return when {
                s < Straight -> Xy(-half + s, -Radius)
                s < Straight + bend -> {
                    val a = (s - Straight) / Radius
                    Xy(half + Radius * sin(a), -Radius * cos(a))
                }
                s < 2 * Straight + bend -> Xy(half - (s - Straight - bend), Radius)
                else -> {
                    val a = (s - 2 * Straight - bend) / Radius
                    Xy(-half - Radius * sin(a), Radius * cos(a))
                }
            }
        }

        val OvalIdentity: TrackRevision = run {
            val bend = PI * Radius
            TrackRevision.first(
                frame,
                path,
                closed = true,
                listOf(
                    SegmentShape(SegmentKind.Straight, 0.0, Straight),
                    SegmentShape(SegmentKind.Corner, Straight, Straight + bend),
                    SegmentShape(SegmentKind.Straight, Straight + bend, 2 * Straight + bend),
                    SegmentShape(SegmentKind.Corner, 2 * Straight + bend, 2 * Straight + 2 * bend),
                ),
            )
        }

        val OvalTrack = Track(
            id = "oval",
            name = "Oval",
            center = OvalCenter,
            startFinish = TrackLine.across(frame.toGeo(Xy(-140.0, -Radius)), 90.0),
            identity = OvalIdentity,
        )
    }
}

@Config(qualifiers = Devices.PhonePortrait)
class CornerEditorPhoneTest : CornerEditorTest("phone-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class CornerEditorTabletTest : CornerEditorTest("tablet-landscape")
