package com.flappedear.betterdriver.smoke

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * Sessions from other apps on the emulator (FBBD-164) and a track made from one (FBBD-151): a
 * Racelogic VBO driven at the circuit opens with its laps; a GPX of a loop where no track is saved
 * offers a track, which is saved from the editor, then times the session and shows in My tracks.
 * The file picker is stubbed with Espresso-Intents, as in FlowSmokeTest.
 */
@RunWith(AndroidJUnit4::class)
class ImportSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Before
    fun stubPickers() = Intents.init()

    @After
    fun releasePickers() {
        Intents.release()
        SmokeData.reset()
    }

    @Test
    fun vboImportOpensWithItsLaps() {
        val vbo = SmokeFixtures.racelogicVbo(SmokeData.corpus(SmokeFixtures.VideoVbo))
        importSession(SmokeData.cacheFile("smoke.vbo", vbo))
        app.waitForText("Imported the VBO session", substring = true, timeoutMs = LoadTimeoutMs)
        app.waitForText("at ${SmokeFixtures.CircuitName}", substring = true).assertIsDisplayed()

        app.openSession(SmokeFixtures.ImportedVbo)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.openTab("Laps")
        app.waitForText("Lap 2")
        app.back()
        app.waitForText("Compare laps")
    }

    @Test
    fun trackFromAnImportedGpx() {
        importSession(SmokeData.cacheFile("smoke.gpx", SmokeFixtures.loopGpx()))
        app.waitForText("Imported the GPX session", substring = true, timeoutMs = LoadTimeoutMs)

        // No track there yet: the analysis offers to make one from the loop.
        app.openSession(SmokeFixtures.ImportedGpx)
        app.waitForText("Make a track from this session", timeoutMs = LoadTimeoutMs).performScrollTo().performClick()
        app.waitForText("Made from your session", substring = true, timeoutMs = LoadTimeoutMs)
        app.waitForText("Rename").performClick()
        app.waitFor(hasSetTextAction()).performTextReplacement(SmokeFixtures.LoopName)
        app.onNode(hasText("Rename") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        app.waitForText(SmokeFixtures.LoopName)
        app.onNode(hasText("Save") and hasClickAction()).performClick()
        app.waitNamed("the track to be saved", LoadTimeoutMs) {
            SmokeData.tracks().any { it.name == SmokeFixtures.LoopName }
        }

        // The session is timed on the new track now. Back one screen at a time until the Sessions list
        // (or Drive) shows, so the last Back never leaves the app.
        Espresso.closeSoftKeyboard()
        repeat(BackSteps) {
            val home = runCatching {
                app.waitNamed("the Sessions list or Drive", SettleMs) {
                    app.showsText("Compare laps") || app.showsText("START")
                }
            }.isSuccess
            if (!home) app.back()
        }
        app.openSessions()
        app.openSession(SmokeFixtures.ImportedGpx)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.back()

        app.openTab("Settings")
        app.openTab("Recording")
        app.waitForText("Tracks and timing lines").performClick()
        app.waitForText("MY TRACKS")
        app.waitForText(SmokeFixtures.LoopName).assertIsDisplayed()
    }

    /** Import session on Sessions, with the picker answering [file]. */
    private fun importSession(file: File) {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        app.openSessions()
        app.waitForText("Import session").performClick()
    }

    private companion object {
        /** The editor and the analysis. */
        const val BackSteps = 2

        /** How long a screen gets to settle after Back. */
        const val SettleMs = 3_000L
    }
}
