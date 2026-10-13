package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.IOException
import java.util.concurrent.Executors

private const val RewriteIntervalMicros = 10_000_000L

/** How often the camera reports its progress (media recorded so far) to the recorder and the clip index. */
private const val SyncIntervalMicros = 1_000_000L

/**
 * Whether a progress report at [nowMicros] is due: the first one at once ([lastMicros] null), then
 * about once a second. A "never reported" of Long.MIN_VALUE would overflow `now - last` to a negative
 * number, and no report would ever be due.
 */
internal fun syncDue(nowMicros: Long, lastMicros: Long?): Boolean =
    lastMicros == null || nowMicros - lastMicros >= SyncIntervalMicros

/**
 * Not measured: how late CameraX reports media progress after the frame. The offset comes from the
 * earliest report, so this is a rough bound until FBBD-51 checks it against Circuit Tools.
 */
private const val UncertaintyMicros = 100_000L

/**
 * Keeps the phone's clips in the session's clip index (`clips.json`, [VideoIndex]) while it records,
 * so a crash of the camera process still leaves every file indexed with its offset.
 *
 * A clip's offset is the earliest "now minus recorded media" seen: a progress report can only arrive
 * late, so the smallest value is the best (the same rule as the recorder's VideoTimeline). When the
 * report also says when the newest frame was captured, "frame time minus recorded media" is late too
 * but closer, by 50 to 94 ms on Arek's 2026-10-13 drive (FBBD-218), so the earlier of the two counts.
 * It is rewritten when the offset improves and every 10 s for the duration. Writes run on their own
 * thread.
 *
 * Main thread for the calls.
 */
internal class ClipIndexer(private val index: VideoIndex) {
    private class Clip(var offset: Long, var duration: Long? = null, var written: Long = Long.MIN_VALUE)

    private val io = Executors.newSingleThreadExecutor { Thread(it, "clip-index").apply { isDaemon = true } }
    private val clips = HashMap<String, Clip>()

    fun started(file: String, nowMicros: Long) {
        clips[file] = Clip(nowMicros)
        write(file, nowMicros)
    }

    fun synced(file: String, nowMicros: Long, mediaMicros: Long, frameMicros: Long? = null) {
        val c = clips[file] ?: return
        val estimate = minOf(nowMicros, frameMicros ?: nowMicros) - mediaMicros
        // The first progress report replaces the start report's estimate, as in VideoTimeline.
        val changed = c.duration == null || estimate < c.offset
        if (changed) c.offset = estimate
        c.duration = mediaMicros
        if (changed || nowMicros - c.written >= RewriteIntervalMicros) write(file, nowMicros)
    }

    fun finished(file: String, mediaMicros: Long) {
        val c = clips[file] ?: return
        if (mediaMicros > 0) c.duration = mediaMicros
        write(file, Long.MAX_VALUE)
    }

    private fun write(file: String, nowMicros: Long) {
        val c = clips[file] ?: return
        c.written = nowMicros
        val clip = VideoClip(
            fileName = file,
            kind = VideoSourceKind.PHONE,
            sourceId = CameraIds.PHONE,
            sessionOffsetUs = c.offset,
            alignment = Alignment(AlignmentMethod.CAPTURE_CLOCK, UncertaintyMicros),
            durationUs = c.duration,
        )
        io.execute {
            try {
                index.put(clip)
            } catch (e: IOException) {
                DebugLog.w("ClipIndexer", "could not index $file", e)
            }
        }
    }
}
