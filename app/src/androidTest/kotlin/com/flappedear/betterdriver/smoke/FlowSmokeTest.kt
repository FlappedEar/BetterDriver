package com.flappedear.betterdriver.smoke

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * The 0.3.0 flows on the emulator (FBBD-161): the lap review comparing two laps, the Export menu
 * (CSV, GPX, VBO, VBO + video and Video with data), and a backup and restore round trip. Android's
 * file picker and share sheet are stubbed with Espresso-Intents: the picker answers as if the user
 * picked a file in the app's cache, so the flows run without the system's own screens.
 */
@RunWith(AndroidJUnit4::class)
class FlowSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Before
    fun stubPickers() = Intents.init()

    @After
    fun releasePickers() = Intents.release()

    @Test
    fun lapReviewComparesTwoLaps() {
        app.openSessions()
        // The lap review opens on the picker with nothing picked, never on an earlier comparison.
        app.waitForText("Compare laps").performClick()
        expectPickerWithNothingPicked()

        // The picker: the circuit's sessions, newest first, with their laps. Pick two and compare them.
        // The lap review runs a frame loop, so the clock is advanced by hand while it shows.
        pickLaps2And3()
        app.withManualClock {
            app.onNode(hasText("Compare") and hasClickAction()).performClick()
            app.pumpUntilText("Change laps", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
            app.pumpUntilText("Lap 2", substring = true)
            // Both laps play side by side with their video.
            app.pumpUntilText("Play").performClick()
            app.pumpUntilText("Pause")
            app.pumpFor(PlayMs)
            app.pumpUntilText("Pause").performClick()
            app.pumpUntilText("Play")
            // Back goes to the picker with nothing picked.
            app.pumpBack()
            app.pumpUntilNode(hasText("Compare") and hasClickAction())
        }
        expectPickerWithNothingPicked()

        // Change laps also goes to the picker with nothing picked.
        pickLaps2And3()
        app.withManualClock {
            app.onNode(hasText("Compare") and hasClickAction()).performClick()
            app.pumpUntilText("Change laps", timeoutMs = LoadTimeoutMs).performClick()
            app.pumpUntilNode(hasText("Compare") and hasClickAction())
        }
        expectPickerWithNothingPicked()

        // Back to Sessions and in again: the picker, not the last comparison.
        app.back()
        app.waitForText("Compare laps").performClick()
        expectPickerWithNothingPicked()
    }

    /** The lap picker is showing, with both slots empty and Compare off. */
    private fun expectPickerWithNothingPicked() {
        app.waitFor(hasText("Compare") and hasClickAction(), LoadTimeoutMs).assertIsNotEnabled()
        assertEquals(2, app.countNodes(hasText("Tap a lap")))
    }

    private fun pickLaps2And3() {
        app.waitForText(SmokeFixtures.CircuitName, timeoutMs = LoadTimeoutMs).performClick()
        app.waitFor(hasText("Lap 2") and hasClickAction(), LoadTimeoutMs).performClick()
        app.waitFor(hasText("Lap 3") and hasClickAction()).performClick()
        app.onNode(hasText("Compare") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun exportMenuCsvGpxVboAndVideo() {
        app.openSessions()

        // Each export is written to the app's cache, then the share sheet opens, as Share log (FBBD-443).
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_OK, null))
        openExportMenu()
        app.waitForText("VBO + video").assertIsDisplayed()
        app.waitForText("VBO").assertIsDisplayed()
        app.waitForText("GPX").assertIsDisplayed()
        app.waitForText("Video with data").assertIsDisplayed()
        app.waitForText("CSV").performClick()
        val csv = exported("csv")
        check(csv.readText().startsWith("time,")) { "not a CSV file: ${csv.readText().take(SnippetChars)}" }
        intended(shares(Intent.ACTION_SEND, "text/csv"))

        // GPX: one track segment per lap.
        openExportMenu()
        app.waitForText("GPX").performClick()
        val text = exported("gpx").readText()
        check("<gpx" in text && "<trkseg>" in text) { "not a GPX file: ${text.take(SnippetChars)}" }
        intended(shares(Intent.ACTION_SEND, "application/gpx+xml"))

        // Plain VBO of a session with video (FBBD-227): one file, no video columns.
        openExportMenu()
        app.waitForText("VBO").performClick()
        val vboText = exported("vbo").readText()
        check("[data]" in vboText && "avifileindex" !in vboText) { "not a plain VBO: ${vboText.take(SnippetChars)}" }
        intended(shares(Intent.ACTION_SEND, "application/octet-stream"))

        // VBO + video: the VBO and its video files side by side, shared together (FBBD-25).
        openExportMenu()
        app.waitForText("VBO + video").performClick()
        val withVideo = exported("vbo")
        check("[avi]" in withVideo.readText()) { "no [avi] section in the VBO + video export" }
        app.waitNamed("the export's video", LoadTimeoutMs) {
            withVideo.parentFile?.listFiles()?.any { it.name.endsWith(".MP4") && it.length() > 0 } == true
        }
        intended(shares(Intent.ACTION_SEND_MULTIPLE, "*/*"))

        // Video with data: the sheet lists the whole session and its laps; Cancel closes it.
        openExportMenu()
        app.waitForText("Video with data").performClick()
        app.waitForText("Whole session", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("Lap 2", substring = true)
        app.waitForText("Cancel").performClick()
        app.waitUntilGone("Whole session")
    }

    @Test
    fun backupAndRestoreRoundTrip() {
        val file = File(cache, "smoke.betterdriver-backup").apply { delete() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(picked(file))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(picked(file))
        app.openSettingsTopic("App")

        // Back up, with the sessions, to the file.
        app.waitForText("Back up").performScrollTo().performClick()
        app.waitForText("3 sessions", substring = true, timeoutMs = LoadTimeoutMs)
        app.waitForText("Include sessions (without video)").performClick()
        app.waitForText("Choose file").performClick()
        app.waitForText("Backed up.", timeoutMs = LoadTimeoutMs)
        check(file.length() > 0) { "the backup is empty" }

        // Restore it: the sheet shows what it holds; Merge keeps everything here.
        app.waitForText("Restore").performScrollTo().performClick()
        app.waitForText("Restore backup", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.waitForText("Merge").performScrollTo().performClick()
        app.waitForText("Restored", substring = true, timeoutMs = LoadTimeoutMs)

        // The data is all still there.
        app.openTab("Sessions")
        app.waitForText(SmokeFixtures.VideoCar, substring = true)
        app.openSession(SmokeFixtures.VideoCar)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs)
    }

    /** Opens the Export menu of the newest session, the one with video, in its ⋮ sheet (UI-139 C). */
    private fun openExportMenu() {
        app.openNewestSessionActions()
        app.waitForText("Export ▾").performClick()
        app.waitForText("CSV")
    }

    /**
     * The export file with [extension] waiting for the share sheet, once its export has ended: each
     * export clears the folder first, so it is the newest.
     */
    private fun exported(extension: String): File {
        var found: File? = null
        app.waitNamed("the $extension export", LoadTimeoutMs) {
            found = File(cache, "exports-out").walk()
                .firstOrNull { it.isFile && it.extension == extension && it.length() > 0 }
            found != null && !app.showsText("Exporting…")
        }
        return checkNotNull(found)
    }

    /** The share sheet, opened for [action] (one file or several) of [type]. */
    private fun shares(action: String, type: String) = allOf(
        hasAction(Intent.ACTION_CHOOSER),
        hasExtra(equalTo(Intent.EXTRA_INTENT), allOf(hasAction(action), hasType(type))),
    )

    /** The picker's answer when the user picks [file]. */
    private fun picked(file: File) = ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))

    private companion object {
        const val PlayMs = 2_000L
        const val SnippetChars = 200
    }
}
