package com.flappedear.betterdriver.smoke

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.review.indexedClips
import com.flappedear.betterdriver.review.sessionVideo
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.videoexport.OptimalLap
import com.flappedear.betterdriver.videoexport.VideoExportEnd
import com.flappedear.betterdriver.videoexport.VideoExports
import com.flappedear.betterdriver.videoexport.fileDurations
import com.flappedear.betterdriver.videoexport.optimalVideo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The optimal lap's video rendered on the emulator (FBBD-163), from [ReviewFixtures]' session A,
 * whose frame-numbered phone video covers both timed laps: Optimal lap video › Export, the file
 * picker answered with a file in the cache, then the MP4 is read back.
 *
 * - The file exists and has a video track.
 * - Its length is the theoretical best within 1 frame per cut (and 1 for the last frame).
 * - Every sampled frame is the source frame its cut should show (within 2 frames: the sample's own
 *   frame and the source's), and the source frames run through the lap in track order.
 */
@RunWith(AndroidJUnit4::class)
class OptimalLapRenderTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun seed() {
        Intents.init()
        SmokeData.seedReview()
    }

    @After
    fun reset() {
        Intents.release()
        SmokeData.reset()
    }

    @Test
    fun optimalLapVideoRendersThePartsInTrackOrder() {
        val id = SmokeData.sessionOf(ReviewFixtures.SyncCarA)
        val out = File(context.cacheDir, "optimal-render-test.mp4").apply { delete() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(out))))

        app.openSessions()
        app.openSession(ReviewFixtures.SyncCarA)
        app.waitForText("BEST LAP", timeoutMs = LoadTimeoutMs)
        app.waitFor(hasText("Optimal lap video") and hasClickAction() and isEnabled(), LoadTimeoutMs)
            .performScrollTo()
            .performClick()
        app.waitFor(hasText("Export") and hasClickAction() and isEnabled(), LoadTimeoutMs).performClick()
        app.waitNamed("the optimal lap video to render", RenderTimeoutMs) { ended(id) }
        val end = VideoExports.get(context).states[id]
        check(end?.text == VideoExportEnd.Saved.text) { "the optimal lap video did not render: ${end?.text}" }
        check(out.isFile && out.length() > 0) { "no optimal lap video at $out" }

        checkRendered(id, out)
    }

    private fun ended(id: String): Boolean {
        val exports = VideoExports.get(context)
        val state = exports.states[id]
        return exports.running == null && state != null && !state.running
    }

    /** The rendered [file] against the plan the app makes from the same session. */
    private fun checkRendered(id: String, file: File) {
        val store = SmokeData.store()
        val data = store.read(id)
        val dir = store.dir(id)
        val track = SmokeData.tracks().first { it.id == ReviewFixtures.SyncRingId }
        val video = checkNotNull(sessionVideo(dir, VideoTimeline.from(data.events, indexedClips(dir))))
        val analysis = analyzeSession(telemetrySessionOf(data, track), identity = track.identity)
        val origin = data.sampleSets.minOf { it.tMicros }
        val plan = checkNotNull(optimalVideo(analysis, video, origin, fileDurations(video)).plan) {
            "the session has no optimal lap on video"
        }
        check(plan.cuts.isNotEmpty()) { "the optimal lap has no cut on video" }

        // Its length: the theoretical best, within a frame per cut and one for the last frame.
        val theoretical = Math.round(plan.lap.totalSeconds * MicrosPerSecond)
        val length = videoTrackMicros(file)
        val allowed = (plan.cuts.size + 1) * FrameMicros
        check(abs(length - theoretical) <= allowed) {
            "the optimal lap video lasts $length µs, the theoretical best is $theoretical µs " +
                "(${plan.cuts.size} cuts, allowed ± $allowed µs)"
        }

        // Each cut shows its own stretch of the session, and the stretches follow the track.
        val laps = replayLaps(id, data.sampleSets, track).laps
        val retriever = FrameCode.retriever(file)
        try {
            var lastAlong = Double.NEGATIVE_INFINITY
            var t = SampleStepMicros / 2
            while (t < length - FrameMicros) {
                val cut = plan.cuts.last { it.cut.outputStartMicros <= t }.cut
                val source = checkNotNull(FrameCode.inFile(retriever, t)) {
                    "unreadable frame at $t µs of the optimal lap video"
                }
                val shown = source * MicrosPerSecond / ReviewFixtures.Fps
                val wanted = cut.sessionMicrosAt(t)
                check(abs(shown - wanted) <= 2 * FrameMicros) {
                    "at $t µs the optimal lap video shows session time $shown µs, its cut wants $wanted µs"
                }
                val lap = laps.firstOrNull { shown >= it.lap.startMicros && shown < it.lap.endMicros }
                if (lap != null) {
                    // Across the line (a part that runs into the next lap) the position starts again at 0.
                    var along = lap.at(shown - lap.lap.startMicros).distanceMeters / lap.lengthMeters
                    if (along < lastAlong - WrapFraction) along += 1.0
                    check(along >= lastAlong - OrderTolerance) {
                        "at $t µs the optimal lap video goes back along the track: $lastAlong → $along of a lap"
                    }
                    lastAlong = along
                }
                t += SampleStepMicros
            }
        } finally {
            retriever.release()
        }
    }

    private fun videoTrackMicros(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    return format.getLong(MediaFormat.KEY_DURATION)
                }
            }
        } finally {
            extractor.release()
        }
        throw AssertionError("the optimal lap video has no video track")
    }

    private companion object {
        /** Rendering about 40 s of small video with its overlay on a CI emulator. */
        const val RenderTimeoutMs = 180_000L
        const val MicrosPerSecond = 1_000_000L
        const val FrameMicros = MicrosPerSecond / ReviewFixtures.Fps + 1
        const val SampleStepMicros = 500_000L

        /** A drop of more than half a lap in the position is the start line, not going back. */
        const val WrapFraction = 0.5

        /** Positions are interpolated between fixes; this much back is still in order. */
        const val OrderTolerance = 0.005
    }
}
