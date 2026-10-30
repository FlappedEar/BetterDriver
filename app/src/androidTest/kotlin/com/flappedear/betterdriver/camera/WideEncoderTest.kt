package com.flappedear.betterdriver.camera

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The 2.7K encoder path (FBBD-246, FBBD-248) on the emulator, at a small size it can encode in real
 * time: frames drawn into [WideEncoder]'s surface as a camera would, a test tone as the microphone,
 * and a small file limit so it cuts files every second or so.
 *
 * - At least 95 % of the frames drawn are in the files, and each file opens with a keyframe. The
 *   emulator's encoder drops a few frames under load (237 of 241 failed the 0.4.4 and 1.1.0 release
 *   gates); since 2.7K was dropped (Arek, 2026-10-20, FBBD-246 Won't do) every frame is no longer
 *   required, and the share matches the drive report's frame-rate criterion.
 * - Files follow each other with no missing frame: the next starts one frame after the last ends.
 * - Each file has sound from its start to its end, and stays under the size limit.
 */
@RunWith(AndroidJUnit4::class)
class WideEncoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "wide-encoder-test")
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
    fun filesFollowEachOtherWithEveryFrameAndSound() {
        val events = Recorded()
        val encoder = WideEncoder(target, Tone(), System::nanoTime, events, cutMarginBytes = 400_000)
        events.encoder = encoder
        encoder.start(fileNo(1))
        val drawn = draw(encoder, seconds = 8)
        val last = checkNotNull(encoder.stop()) { "no file was opened" }
        assertEquals("problems the encoder reported", emptyList<String>(), events.problems.toList())

        val files = dir.listFiles().orEmpty().filter { it.length() > 0 }.sortedBy { it.name }
        assertTrue("several files: ${files.map { it.name }}", files.size >= 3)
        assertEquals("handovers", files.size - 1, events.handovers.size)
        val samples = files.map { read(it) }
        files.zip(samples).forEach { (f, s) ->
            assertTrue("${f.name} under the limit", f.length() < target.maxFileBytes)
            assertTrue("${f.name} opens with a keyframe", s.firstKey)
            assertTrue("${f.name} has sound", s.audioUs.isNotEmpty())
            assertTrue("${f.name}: sound from the start (${s.audioUs.first()} µs)", s.audioUs.first() <= SoundSlackUs)
            val end = s.videoUs.last()
            val endOk = s.audioUs.last() >= end - SoundSlackUs
            assertTrue("${f.name}: sound to the end (${s.audioUs.last()} of $end µs)", endOk)
        }
        val frames = samples.sumOf { it.videoUs.size }
        assertTrue("$frames of $drawn frames in the files", frames >= drawn * MinFrameShare)

        // Each handover: the next file starts one frame after the last frame of the one before.
        events.handovers.forEach { h ->
            val doneBase = events.bases.getValue(h.done)
            val gapUs = h.nextBaseNanos / 1_000 - (doneBase / 1_000 + h.mediaMicros)
            assertTrue("${h.done} to ${h.next}: $gapUs µs between frames", gapUs > 0 && gapUs < FrameUs * 5 / 2)
        }
        assertTrue(last.stats.audio)
        assertEquals(files.last().name, last.file.name)
    }

    /**
     * Audit A15: a writer stuck on I/O while the camera keeps drawing. The copies waiting for it stay in
     * the budget, the encoder reports the stalled writer instead of queueing on, and once the writer
     * comes back the file is finished and plays.
     */
    @Test
    fun stuckWriterEndsTheRecordingWithinTheBudget() {
        val events = Recorded()
        val stuck = CountDownLatch(1)
        val budgetKib = 2_048 // eight video copies of at least 256 KiB each
        val encoder = WideEncoder(
            target,
            Tone(),
            System::nanoTime,
            events,
            backlogKib = budgetKib,
            backlogWaitMs = 200,
            beforeWrite = { stuck.await() },
        )
        events.encoder = encoder
        encoder.start(fileNo(1))
        try {
            draw(encoder, seconds = 3)
        } finally {
            stuck.countDown()
        }
        val last = encoder.stop()
        val reported = events.problems.toList()
        assertEquals("the stalled writer is reported once", listOf("encoder failed: writer stalled"), reported)
        val file = checkNotNull(last) { "the file was opened before the writer stalled" }
        assertEquals(null, file.error)
        assertTrue("the file holds the frames written before the stall", read(file.file).videoUs.isNotEmpty())
        assertTrue("${file.stats.frames} frames: no more than the budget held", file.stats.frames <= budgetKib / 256)
    }

    /**
     * Audit 30 Oct BD-04: a write that throws on the writer thread. The failure is reported once as
     * the writer's, not as a stalled writer later, the copies' room is given back and stop() ends.
     */
    @Test
    fun throwingWriteIsReportedAndStopEnds() {
        val events = Recorded()
        val encoder = WideEncoder(
            target,
            Tone(),
            System::nanoTime,
            events,
            backlogKib = 2_048,
            backlogWaitMs = 200,
            beforeWrite = { throw IllegalStateException("injected write fault") },
        )
        events.encoder = encoder
        encoder.start(fileNo(1))
        draw(encoder, seconds = 2)
        encoder.stop()
        assertEquals(
            "the write fault is reported once, never as a stall",
            listOf("encoder failed: writer: IllegalStateException"),
            events.problems.toList(),
        )
    }

    private fun fileNo(n: Int) = File(dir, "phone_%04d.mp4".format(n))

    /** Draws numbered frames at [Fps] for [seconds], as the camera would; returns how many. */
    private fun draw(encoder: WideEncoder, seconds: Int): Int {
        val paint = Paint().apply { textSize = 120f }
        val start = System.nanoTime()
        var n = 0
        while (System.nanoTime() - start < seconds * 1_000_000_000L) {
            val canvas = encoder.surface.lockHardwareCanvas()
            canvas.drawColor(if (n % 2 == 0) Color.DKGRAY else Color.LTGRAY)
            paint.color = Color.RED
            canvas.drawText("$n", 40f, 200f, paint)
            encoder.surface.unlockCanvasAndPost(canvas)
            n++
            val due = start + n * FrameUs * 1_000
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
        return n
    }

    private class Samples(val videoUs: List<Long>, val audioUs: List<Long>, val firstKey: Boolean)

    private fun read(file: File): Samples {
        val video = mutableListOf<Long>()
        val audio = mutableListOf<Long>()
        var firstKey = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            for (t in 0 until extractor.trackCount) extractor.selectTrack(t)
            val mimes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
            }
            while (extractor.sampleTrackIndex >= 0) {
                val isVideo = mimes[extractor.sampleTrackIndex]?.startsWith("video/") == true
                val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (isVideo && video.isEmpty()) firstKey = sync
                (if (isVideo) video else audio) += extractor.sampleTime
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
        return Samples(video.sorted(), audio.sorted(), firstKey)
    }

    private data class Handover(val done: String, val mediaMicros: Long, val next: String, val nextBaseNanos: Long)

    /** What the encoder reported; asks for the next file at once, as the app does on the main thread. */
    private inner class Recorded : WideEncoder.Events {
        lateinit var encoder: WideEncoder
        private var index = 1
        val handovers: MutableList<Handover> = Collections.synchronizedList(mutableListOf())
        val bases: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())
        val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun nextWanted() {
            index++
            encoder.offerNext(fileNo(index))
        }

        override fun started(file: File, baseNanos: Long) {
            bases[file.name] = baseNanos
        }

        override fun handedOver(done: File, mediaMicros: Long, next: File, baseNanos: Long) {
            bases[next.name] = baseNanos
            handovers += Handover(done.name, mediaMicros, next.name, baseNanos)
        }

        override fun closed(file: File, stats: WideEncoder.FileStats, error: String?) {
            if (error != null) problems += "${file.name}: $error"
        }

        override fun full() {
            problems += "a file reached its limit with no next one"
        }

        override fun failed(error: String) {
            problems += "encoder failed: $error"
        }
    }

    /** A 440 Hz tone at 48 kHz, read at the pace a microphone gives it, timed on the monotonic clock. */
    private class Tone : PcmSource {
        override val sampleRate = 48_000
        override val channels = 1
        private var start = 0L
        private var frames = 0L

        @Volatile private var stopped = false

        override fun start() {
            start = System.nanoTime()
        }

        override fun read(into: ByteBuffer): PcmRead? {
            if (stopped) return null
            val n = into.remaining() / 2
            val at = start + frames * 1_000_000_000L / sampleRate
            val ready = at + n * 1_000_000_000L / sampleRate
            val wait = (ready - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            for (i in 0 until n) {
                val v = (sin(2 * PI * 440 * (frames + i) / sampleRate) * 8_000).toInt().toShort()
                into.put((v.toInt() and 0xff).toByte())
                into.put((v.toInt() shr 8).toByte())
            }
            frames += n
            return PcmRead(n * 2, at)
        }

        override fun stop() {
            stopped = true
        }
    }

    private companion object {
        const val Fps = 30
        const val FrameUs = 1_000_000L / Fps
        const val SoundSlackUs = 60_000L
        const val MinFrameShare = 0.95
    }
}
