package com.flappedear.betterdriver.smoke

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * The 0.3.0 flows on the emulator (FBBD-161): the lap review comparing two laps, the Export menu
 * (CSV, GPX and Video with data), and a backup and restore round trip. Android's file picker is
 * stubbed with Espresso-Intents: it answers as if the user cancelled or picked a file in the app's
 * cache, so the flows run without the system picker.
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
        app.waitForText("Compare laps").performClick()
        // The picker: the circuit's sessions, newest first, with their laps.
        app.waitForText(SmokeFixtures.CircuitName, timeoutMs = LoadTimeoutMs).performClick()
        app.waitFor(hasText("Lap 2") and hasClickAction(), LoadTimeoutMs).performClick()
        app.waitFor(hasText("Lap 3") and hasClickAction()).performClick()
        app.onNode(hasText("Compare") and hasClickAction()).assertIsEnabled().performClick()
        app.waitForText("Change laps", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        // Both laps play side by side with their video.
        app.waitForText("Play").performClick()
        app.waitForText("Pause")
        Thread.sleep(PlayMs)
        app.waitForText("Pause").performClick()
        // Back to the picker, the two laps still picked.
        app.waitForText("Change laps").performClick()
        app.waitFor(hasText("Compare") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun exportMenuCsvGpxAndVideo() {
        app.openSessions()

        // CSV: the save dialog opens; cancelled, nothing is written.
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        openExportMenu()
        app.waitForText("VBO + video").assertIsDisplayed()
        app.waitForText("GPX").assertIsDisplayed()
        app.waitForText("Video with data").assertIsDisplayed()
        app.waitForText("CSV").performClick()
        intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType("text/csv")))
        app.waitForIdle()
        check(app.onAllNodesWithText("Exporting…").fetchSemanticsNodes().isEmpty()) { "a cancelled export ran" }

        // GPX: saved to a file in the cache, one track segment per lap.
        val gpx = File(cache, "smoke.gpx").apply { delete() }
        intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType("application/gpx+xml")))
            .respondWith(picked(gpx))
        openExportMenu()
        app.waitForText("GPX").performClick()
        app.waitForText("Saved", timeoutMs = LoadTimeoutMs)
        val text = gpx.readText()
        check("<gpx" in text && "<trkseg>" in text) { "not a GPX file: ${text.take(SnippetChars)}" }

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
        app.openTab("Settings")
        app.openTab("App")

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
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs)
    }

    /** Opens the Export menu of the newest session, the one with video. */
    private fun openExportMenu() {
        app.waitForText("Export ▾").performClick()
        app.waitForText("CSV")
    }

    /** The picker's answer when the user picks [file]. */
    private fun picked(file: File) = ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))

    private companion object {
        const val PlayMs = 2_000L
        const val SnippetChars = 200
    }
}
