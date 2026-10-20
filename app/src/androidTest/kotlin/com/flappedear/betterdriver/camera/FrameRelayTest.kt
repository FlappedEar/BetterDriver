package com.flappedear.betterdriver.camera

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The relay between the camera and the 2.7K encoder (FBBD-246) on the emulator: frames drawn into
 * the relay's surface, as the camera would, reach the encoder's files, every one of them, on time,
 * across file handovers, with none lost on the way.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class FrameRelayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "frame-relay-test")
    private val target = WideTarget(640, 360, Fps, 2_000_000, 0, maxFileBytes = 700_000)

    @Before
    fun clean() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tidy() {
        dir.deleteRecursively()
    }

    @Test
    fun everyFrameDrawnIntoTheRelayReachesTheFiles() {
        var index = 1
        lateinit var encoder: WideEncoder
        val problems = mutableListOf<String>()
        val events = object : WideEncoder.Events {
            override fun nextWanted() {
                index++
                encoder.offerNext(file(index))
            }

            override fun started(file: File, baseNanos: Long) = Unit

            override fun handedOver(done: File, mediaMicros: Long, next: File, baseNanos: Long) = Unit

            override fun closed(file: File, stats: WideEncoder.FileStats, error: String?) {
                if (error != null) synchronized(problems) { problems += "${file.name}: $error" }
            }

            override fun full() {
                synchronized(problems) { problems += "a file reached its limit with no next one" }
            }

            override fun failed(error: String) {
                synchronized(problems) { problems += "encoder failed: $error" }
            }
        }
        encoder = WideEncoder(target, null, System::nanoTime, events, cutMarginBytes = 400_000)
        encoder.start(file(1))
        val relay = FrameRelay(encoder.surface, target.width, target.height, softwareEncoder = encoder.softwareOnly)
        val began = System.nanoTime()
        val drawn = draw(relay, seconds = 6)
        val drawnNanos = System.nanoTime() - began
        // Closed at once, as the recorder does when the camera stops: frames still on their way are kept.
        relay.close()
        encoder.stop()
        assertEquals("problems the encoder reported", emptyList<String>(), synchronized(problems) { problems.toList() })

        val counts = "${relay.counts()}, ${encoder.setup} software=${encoder.softwareOnly}"
        assertTrue(counts, relay.counts().endsWith("relay_failed=0"))
        val files = dir.listFiles().orEmpty().filter { it.length() > 0 }.sortedBy { it.name }
        assertTrue("several files: ${files.map { it.name }} ($counts)", files.size >= 2)
        val times = files.map { frameTimes(it) }
        val frames = times.sumOf { it.size }
        assertTrue("$frames of $drawn frames in the files ($counts)", frames >= drawn - 2)
        // On the monotonic clock the frames were drawn on: each file spans what was drawn into it.
        val spanUs = times.sumOf { it.last() - it.first() }
        assertTrue("files span $spanUs µs of ${drawnNanos / 1_000} µs drawn", spanUs <= drawnNanos / 1_000)
        times.forEach { t ->
            val gaps = t.zipWithNext { a, b -> b - a }
            assertTrue("frame times rise", gaps.all { it > 0 })
        }
    }

    private fun file(n: Int) = File(dir, "phone_%04d.mp4".format(n))

    private fun draw(relay: FrameRelay, seconds: Int): Int {
        val start = System.nanoTime()
        var n = 0
        while (System.nanoTime() - start < seconds * 1_000_000_000L) {
            val canvas = relay.surface.lockHardwareCanvas()
            canvas.drawColor(if (n % 2 == 0) Color.DKGRAY else Color.LTGRAY)
            relay.surface.unlockCanvasAndPost(canvas)
            n++
            val wait = (start + n * 1_000_000_000L / Fps - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
        return n
    }

    private fun frameTimes(file: File): List<Long> {
        val times = mutableListOf<Long>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val video = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(video)
            while (extractor.sampleTrackIndex >= 0) {
                times += extractor.sampleTime
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
        return times.sorted()
    }

    private companion object {
        const val Fps = 30
    }
}
