package com.flappedear.betterdriver.smoke

import android.content.pm.ActivityInfo
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.metrics.performance.JankStats
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.feature.track.ExampleTrackState
import com.flappedear.betterdriver.feature.track.TrackScreenContent
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.feature.track.TrackTile
import com.flappedear.betterdriver.recording.formatClock
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.Locale
import kotlin.math.sin

/**
 * The track screen's work per update (FBBD-266), on the emulator: the screen in landscape, fed a
 * new state 4 times a second as the recorder sends it, the lap clock, delta, speed and G changing
 * as on a lap. Logs the UI thread's time per frame (JankStats, as the drive's log counts it) and
 * the bytes the app allocates per update, as "TrackFrames" lines the smoke script prints. The
 * emulator's times are not the phone's; the line is for comparing builds on the same runner. The
 * garbage per update is the emulator's as much as the phone's, so it is held under a limit: 66 KB
 * before numbers kept their laid-out characters, 28 KB after.
 */
@RunWith(AndroidJUnit4::class)
class TrackScreenFramesTest {
    @Test
    fun trackScreenUpdates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            SystemClock.sleep(SettleMs)
            val frames = Collections.synchronizedList(mutableListOf<Long>())
            var measuring = false
            var updates = 0
            scenario.onActivity { activity ->
                activity.setContent {
                    var state by remember { mutableStateOf(lapState(0)) }
                    LaunchedEffect(Unit) {
                        var tick = 0
                        while (true) {
                            delay(TickMs)
                            tick++
                            state = lapState(tick)
                            if (measuring) updates++
                        }
                    }
                    BetterDriverTheme(track = true) { TrackScreenContent(state) }
                }
                JankStats.createAndTrack(activity.window) { f -> if (measuring) frames += f.frameDurationUiNanos }
            }
            SystemClock.sleep(WarmUpMs)
            val allocatedBefore = allocatedBytes()
            scenario.onActivity { measuring = true }
            SystemClock.sleep(MeasureMs)
            scenario.onActivity { measuring = false }
            val allocated = allocatedBytes() - allocatedBefore
            val ms = synchronized(frames) { frames.map { it / NanosPerMs }.sorted() }
            assertTrue("the track screen drew no frames", ms.isNotEmpty())
            Log.i(
                Tag,
                String.format(
                    Locale.ROOT,
                    "updates=%d frames=%d ui_ms p50=%.2f p95=%.2f max=%.2f mean=%.2f alloc_kb_per_update=%.1f",
                    updates,
                    ms.size,
                    ms.percentile(0.50),
                    ms.percentile(0.95),
                    ms.last(),
                    ms.average(),
                    if (updates > 0) allocated / 1024.0 / updates else -1.0,
                ),
            )
            assertTrue(
                "the track screen allocates ${allocated / 1024 / updates} KB per update (limit $MaxKbPerUpdate)",
                allocated < 0 || updates == 0 || allocated / 1024 / updates <= MaxKbPerUpdate,
            )
        }
    }

    /** Bytes the app's process has allocated so far, as ART counts them; -1 when ART does not say. */
    private fun allocatedBytes(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L

    private fun List<Double>.percentile(p: Double): Double = this[((size - 1) * p).toInt()]

    /** A made-up lap, [tick] updates (250 ms each) in: every slot the recorder fills changes. */
    private fun lapState(tick: Int): TrackScreenState {
        val lapMs = (tick * TickMs) % LapMs
        val lap = 2 + tick * TickMs / LapMs
        val delta = sin(tick / 9.0) * 1.3
        return ExampleTrackState.copy(
            status = listOf(StatusItem("Track", "Silesia Ring"), StatusItem("GNSS", "25 Hz · ${16 + tick % 4} sats")),
            recordingTime = formatClock(tick * TickMs * MicrosPerMs),
            deltaSeconds = delta,
            lapNumber = lap.toInt(),
            lapMillis = lapMs,
            sectors = listOf(
                SectorState("S1", -0.21),
                SectorState("S2", if (lapMs > LapMs / 3) 0.08 else null, current = lapMs in LapMs / 3..LapMs * 2 / 3),
                SectorState("S3", null, current = lapMs > LapMs * 2 / 3),
            ),
            tiles = listOf(
                TrackTile("Speed", (90 + (sin(tick / 5.0) * 60).toInt()).toString(), "km/h"),
                TrackTile("G", String.format(Locale.ROOT, "%.2f", 0.6 + sin(tick / 3.0) * 0.5), "g"),
                TrackTile("Lap", lap.toString(), ""),
            ),
            coachCorner = if ((tick / 40) % 2 == 0) "T4" else "T7",
            coachCue = if ((tick / 40) % 2 == 0) "Brake at 100 m · 10 later" else "65 at the apex · 10 more",
        )
    }

    private companion object {
        const val Tag = "TrackFrames"
        const val TickMs = 250L
        const val LapMs = 144_000L
        const val SettleMs = 3_000L
        const val WarmUpMs = 5_000L
        const val MeasureMs = 30_000L
        const val NanosPerMs = 1_000_000.0
        const val MicrosPerMs = 1_000L
        const val MaxKbPerUpdate = 45L
    }
}
