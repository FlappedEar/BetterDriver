package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flappedear.betterdriver.feature.track.editor.SegmentRowTag
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.tracks.Tracks
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * A corner corrected in the track editor (FBBD-179), on the emulator: named there, it is stored at
 * once and every session at the track is analysed with it, here another session than the one that
 * found the corners. The track's corners are put back afterwards, as the other tests expect them.
 */
@RunWith(AndroidJUnit4::class)
class CornerCorrectionSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val trackFile get() = File(context.filesDir, "tracks/${SmokeFixtures.CircuitId}.track.json")

    /** The circuit's file before the test, written back after it. */
    private var before: String? = null

    private fun circuit(): Track = SmokeData.tracks().first { it.id == SmokeFixtures.CircuitId }

    @Before
    fun keepTrack() {
        before = trackFile.takeIf { it.isFile }?.readText()
    }

    @After
    fun putTrackBack() {
        before?.let { trackFile.writeText(it) }
        Tracks.get(context).refresh()
        SmokeData.reset()
    }

    @Test
    fun aCornerNamedInTheEditorIsNamedSoInEverySessionThere() {
        // The analysis of one session finds the track's corners and keeps them with the track.
        app.openSessions()
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        app.waitNamed("the track's corners") { circuit().identity != null }
        val corner = circuit().identity!!.corners.first()
        app.back()
        app.waitForText("Compare laps")

        // The track editor lists them under the lines; the first one is named.
        app.openSettingsTopic("Tracks")
        app.waitFor(hasText(SmokeFixtures.CircuitName) and hasClickAction()).performClick()
        app.waitForText("Kind", timeoutMs = LoadTimeoutMs)
        app.waitFor(hasTestTag(SegmentRowTag + corner.id)).performScrollTo().performClick()
        app.waitForText("Rename").performScrollTo().performClick()
        app.waitFor(hasSetTextAction()).performTextReplacement("Turn One")
        app.waitForText("Save").performScrollTo().performClick()
        // As in ImportSmokeTest: a keyboard still up would take the first Back.
        Espresso.closeSoftKeyboard()
        app.waitForText("Turn One")
        // Stored at once, without Save.
        app.waitNamed("the stored name") { circuit().identity?.segment(corner.id)?.name == "Turn One" }
        app.waitForText("Done").performClick()
        app.waitForText("Edited").performScrollTo()
        app.back()
        app.waitForText("MY TRACKS")
        app.back()

        // Another session at the track: its analysis names the corner so.
        app.openSessions()
        app.openSession(SmokeFixtures.PlainCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
        app.openTab("Corners")
        app.waitForText("Turn One", substring = true, timeoutMs = LoadTimeoutMs)
        app.back()
        app.waitForText("Compare laps")
    }
}
