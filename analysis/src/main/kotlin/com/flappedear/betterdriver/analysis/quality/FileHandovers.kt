package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.LogLine

// Whether full 2.7K files handed over to the next with no frame lost (FBBD-248): Arek's 2026-10-19
// log on 0.4.0 had the handover at 3.8 GB fail and the video fall back to 1080p, and no check said so.

/** "handover phone_0001.mp4 -> phone_0002.mp4 gap_us=16667 frame_us=16667", from WideEncoder. */
private val HandoverLine = Regex("""^handover (\S+) -> (\S+) gap_us=(-?\d+) frame_us=(\d+)""")

/** WideRecorder's line for a file it could not finish: "phone_0001.mp4: 2688x1512@60 ... error=no valid data". */
private val UnfinishedLine = Regex("""^(\S+\.mp4): \d+x\d+@\d+ .* error=(.+)$""")

/** The next file could not be opened: "phone_0002.mp4 not opened". */
private val UnopenedLine = Regex("""^(\S+\.mp4) not opened""")

/** WideEncoder's stop gave up waiting for a handed-over file to finish: it may never have been. */
private val UnfinishedAtStopLine = Regex("""^a handed-over file was still finishing after""")

private const val HandoverTag = "WideRecorder"
private const val HandoverDone = 1
private const val HandoverNext = 2
private const val HandoverGap = 3
private const val HandoverFrame = 4
private const val HandoverCriterion =
    "each next file starts within 2.5 frame times of the last frame of the full one, every handed-over file " +
        "finished (FBBD-248)"

/**
 * The encoder cuts at a frame it was given, so a gap over one frame time is a frame the camera itself
 * skipped at that moment (FBBD-246); one such frame is allowed, as in the emulator's encoder test.
 */
private const val MaxGapFrames = 2.5

private class Handover(val done: String, val next: String, val gapMicros: Long, val frameMicros: Long) {
    val ok: Boolean get() = gapMicros > 0 && gapMicros < frameMicros * MaxGapFrames
}

/**
 * The phone's file handovers in [lines]: the largest gap between a full file's last frame and the
 * next file's first, and files that were handed over but never finished, or never opened. Null
 * when the recording had none.
 */
internal fun fileHandovers(lines: List<LogLine>): QualityMetric? {
    val own = lines.filter { it.tag == HandoverTag }
    val handovers = own.mapNotNull { l ->
        HandoverLine.find(l.message)?.groupValues?.let { g ->
            Handover(g[HandoverDone], g[HandoverNext], g[HandoverGap].toLong(), g[HandoverFrame].toLong())
        }
    }
    val unopened = own.mapNotNull { UnopenedLine.find(it.message)?.groupValues?.get(1) }
    if (handovers.isEmpty() && unopened.isEmpty()) return null
    val handedOver = handovers.map { it.done }.toSet()
    val unfinished = own.mapNotNull { UnfinishedLine.find(it.message)?.groupValues }
        .filter { it[1] in handedOver }
        .map { "${it[1]} (${it[2]})" } +
        own.filter { UnfinishedAtStopLine.containsMatchIn(it.message) }
            .map { "a handed-over file still finishing at the stop" }
    val worst = handovers.maxByOrNull { it.gapMicros }
    val how = buildString {
        append("${handovers.size} handover(s), gap from the last frame of each full file to the first of the next")
        if (worst != null) {
            append("; largest ${msText(worst.gapMicros)} ms (frame time ${msText(worst.frameMicros)} ms), ")
            append("${worst.done} to ${worst.next}")
        }
        if (unfinished.isNotEmpty()) append("; not finished: ${unfinished.joinToString(", ")}")
        if (unopened.isNotEmpty()) append("; next file not opened: ${unopened.joinToString(", ")}")
    }
    val ok = handovers.all { it.ok } && unfinished.isEmpty() && unopened.isEmpty()
    val largestMs = (worst?.gapMicros ?: 0L) / MicrosPerMilli.toDouble()
    return judged(
        "video.phone.handover", "Video file handover (phone)", largestMs, "ms", ok, Basis.MEASURED,
        HandoverCriterion, how,
    )
}

private fun msText(micros: Long) = "%.1f".format(java.util.Locale.ROOT, micros / MicrosPerMilli.toDouble())
