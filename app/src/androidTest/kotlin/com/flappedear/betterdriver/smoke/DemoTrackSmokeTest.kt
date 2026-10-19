package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.sessionanalysis.sessionLabel
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.track.TrackEvent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The Demo track receiver (FBBD-233) as Arek uses it without a track day: added under Devices, it
 * saves the demo circuit's track on its own, and a session started with it is timed at that track
 * with the coach process running, as at a real circuit. The session is then one of the Demo track's
 * Drives (FBBD-235).
 */
@RunWith(AndroidJUnit4::class)
class DemoTrackSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun aSessionOnTheDemoTrackIsTimedAtItsTrack() {
        val before = SmokeData.sessions().map { it.id }.toSet()
        app.openTab("Settings")
        app.openTab("Devices")
        app.waitForText("+ Add equipment").performClick()
        app.waitForText("Laps and coach cues on a made-up circuit").performScrollTo().performClick()
        app.waitFor(hasText("Demo track") and hasClickAction())
        app.openTab("Drive")
        app.waitUntilGone("Add a GNSS receiver to start")

        app.waitForText("START").performClick()
        app.withManualClock {
            app.pumpUntil(LoadTimeoutMs, "the recorder and coach processes") {
                appProcesses().containsAll(Processes)
            }
            app.pumpFor(DrivingMs)
            check(appProcessErrors().isEmpty()) { appProcessErrors().joinToString("\n") }
            app.onRoot().performTouchInput { down(center) }
            app.pumpUntilText("Hold to stop", substring = true)
            app.pumpUntilText("START", timeoutMs = LoadTimeoutMs)
            runCatching { app.onRoot().performTouchInput { up() } }
        }
        app.waitNamed("the session to be saved", LoadTimeoutMs) {
            SmokeData.sessions().any { it.id !in before && it.state == SessionState.FINISHED }
        }

        check(SmokeData.tracks().any { it.id == DemoCircuit.TrackId }) { "the demo circuit's track was not saved" }
        val id = SmokeData.sessions().single { it.id !in before }.id
        val events = SmokeData.store().read(id).events
        check(TrackEvent.savedTrackId(events) == DemoCircuit.TrackId) {
            "the session was not timed at the demo circuit: ${events.filter { it.kind == TrackEvent.KIND }}"
        }

        // Arek, 0.3.35: Drives listed only the demo circuit. A session on the phone is one of its choices.
        app.openTab("Settings")
        app.openTab("Devices")
        app.waitFor(hasText("Demo track") and hasClickAction()).performClick()
        app.waitForText("Drives").performClick()
        val start = SmokeData.sessions().single { it.id == id }.info.startUtcMillis
        app.waitForText(sessionLabel(start), substring = true)
    }

    private companion object {
        val Processes = listOf(":recorder", ":coach")

        /** Long enough for the first fix and the track to be picked. */
        const val DrivingMs = 20_000L
    }
}
