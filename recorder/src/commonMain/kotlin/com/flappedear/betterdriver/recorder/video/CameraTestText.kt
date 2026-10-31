package com.flappedear.betterdriver.recorder.video

import com.flappedear.betterdriver.format.fixed

/**
 * One part of the camera test (FBBD-278): the rate the camera delivered, and the share of frames it
 * skipped. Shared by Android and iOS (FBBD-371) so both show and log the same text.
 */
data class CameraTestPart(val fps: Double, val skippedPercent: Double) {
    /** "camera 57.2 fps · skipped 3.1 %", as the test screen shows it (UI-152 A). */
    val text: String get() = "camera ${fixed(fps, 1)} fps · skipped ${fixed(skippedPercent, 1)} %"

    companion object {
        /**
         * The part's result from its frame counts: [frames] the camera delivered over [seconds],
         * [skipped] it dropped or the file could not take. Null before any frame.
         */
        fun of(frames: Long, skipped: Long, seconds: Double): CameraTestPart? {
            if (frames <= 0 || seconds <= 0) return null
            return CameraTestPart(frames / seconds, skipped * Percent / (frames + skipped))
        }

        private const val Percent = 100.0
    }
}

/**
 * The camera test's plan and log lines (FBBD-278, UI-150..152 A), the same on Android and iOS
 * (FBBD-371): 1080p · 60 twice in a row, [PART_MINUTES] each; part 1 writes the MP4 as a session
 * does, part 2 writes no file. Each line starts "camera test", so one search finds the whole test.
 */
object CameraTestText {
    const val PARTS = 2
    const val PART_MINUTES = 12
    const val TAG = "CameraTest"

    /** Part 1 writes the MP4. */
    fun withMp4(part: Int): Boolean = part == 1

    fun partName(part: Int): String = if (withMp4(part)) "with MP4" else "without MP4"

    /** "camera test part 1 of 2: 1080p · 60 with MP4, 19000000 bit/s, sound on, 12 min". */
    fun partStarted(part: Int, mode: String, bitrate: Long, sound: Boolean): String =
        "camera test part $part of $PARTS: $mode ${partName(part)}, $bitrate bit/s, " +
            "sound ${if (sound) "on" else "off"}, $PART_MINUTES min"

    /** "camera test part 1 of 2 with MP4: camera 59.9 fps · skipped 0.1 %", with the error if one ended it. */
    fun partEnded(part: Int, result: CameraTestPart?, error: String?): String =
        "camera test part $part of $PARTS ${partName(part)}: " + (result?.text ?: "no frames") +
            error?.let { " error=$it" }.orEmpty()

    /** "camera test done: part 1 …, part 2 …", with the note when it stopped or failed. */
    fun ended(status: String, first: CameraTestPart?, second: CameraTestPart?, note: String?): String =
        "camera test ${status.lowercase()}: " + listOf(first, second).mapIndexed { i, r ->
            "part ${i + 1} ${r?.text ?: "n/a"}"
        }.joinToString(", ") + note?.let { ", $it" }.orEmpty()

    fun stopping(reason: String): String = "camera test: stopping, $reason"

    /** "m:ss left" of a part, from the milliseconds left. */
    fun left(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / MillisPerSecond
        return "${seconds / SecondsPerMinute}:${(seconds % SecondsPerMinute).toString().padStart(2, '0')} left"
    }

    private const val MillisPerSecond = 1_000L
    private const val SecondsPerMinute = 60L
}
