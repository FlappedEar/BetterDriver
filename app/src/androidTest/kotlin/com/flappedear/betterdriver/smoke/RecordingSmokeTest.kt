package com.flappedear.betterdriver.smoke

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.flappedear.betterdriver.recorder.store.SessionState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * A recording on the emulator, started and stopped as Arek does it: Demo GNSS (the app's own
 * stand-in receiver, a 40 s lap of a circle) and the phone camera, at a saved track whose line the
 * circle crosses. START brings up the recorder, camera and coach processes, each in its own
 * process; they must start and stay up without an error while it drives its laps. A hold on
 * the track screen stops the session, which is then listed and opens with its laps. The emulator
 * has no camera, so the camera process runs and reports that, as on a phone without one.
 */
@RunWith(AndroidJUnit4::class)
class RecordingSmokeTest {
    private val setup = SmokeSetup()

    // Phone video also needs the camera, and the microphone for its sound.
    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        .around(setup.rules)

    private val app get() = setup.compose

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun recordsWithEveryProcess() {
        SmokeData.saveTrack(SmokeFixtures.demoRing)
        val before = SmokeData.sessions().map { it.id }.toSet()
        app.openSettingsTopic("Equipment")
        app.waitForText("+ Add equipment").performClick()
        app.waitForText("For trying the app").performScrollTo().performClick()
        app.waitFor(hasText("Demo GNSS") and hasClickAction())
        app.openTab("Drive")
        app.waitUntilGone("Add a GNSS receiver to start")

        app.waitForText("START").performClick()
        // The track screen animates while it records, so the clock is advanced by hand meanwhile.
        app.withManualClock {
            app.pumpUntil(LoadTimeoutMs, "the recorder, camera and coach processes") {
                appProcesses().containsAll(Processes)
            }
            app.pumpFor(LapsMs)
            val running = appProcesses()
            check(running.containsAll(Processes)) { "a process of the recording ended: $running" }
            check(appProcessErrors().isEmpty()) { appProcessErrors().joinToString("\n") }

            // A hold anywhere stops the session: the ring fills for 3 s, then Drive shows again.
            app.onRoot().performTouchInput { down(center) }
            app.pumpUntilText("Hold to stop", substring = true)
            app.pumpUntilText("START", timeoutMs = LoadTimeoutMs)
            // The finger goes up where it went down, unless the turn back to portrait made a new window.
            runCatching { app.onRoot().performTouchInput { up() } }
        }
        app.waitNamed("the session to be saved", LoadTimeoutMs) {
            SmokeData.sessions().any { it.id !in before && it.state == SessionState.FINISHED }
        }

        val session = SmokeData.sessions().single { it.id !in before }
        app.openSessions()
        app.openSessionRow(session.id)
        app.waitForText("BEST ACTUAL LAP", timeoutMs = LoadTimeoutMs).assertIsDisplayed()
        app.openTab("Laps")
        app.waitForText("Lap 2")
    }

    private companion object {
        val Processes = listOf(":recorder", ":camera", ":coach")

        /** Three crossings of the line, a lap apart: two whole laps. */
        const val LapsMs = 130_000L
    }
}
