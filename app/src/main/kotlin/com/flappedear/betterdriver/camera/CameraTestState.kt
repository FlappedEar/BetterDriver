package com.flappedear.betterdriver.camera

import android.content.Context
import com.flappedear.betterdriver.recorder.video.CameraTestPart
import com.flappedear.betterdriver.recorder.video.CameraTestText
import java.io.File
import java.io.IOException

/** One part of the camera test, shared with iOS (FBBD-371): the camera's rate and the frames it skipped. */
typealias TestPart = CameraTestPart

/**
 * Where the camera test stands (FBBD-278, UI-150..152 A), written by [CameraTest] in the camera's
 * process and read by the test screen in the app's: [part] 1 records with the MP4, 2 without, each
 * [CameraTestState.PartMinutes] long, and each part's camera result once it has one.
 */
data class CameraTestState(
    val status: Status,
    val part: Int,
    val partEndsAtMillis: Long = 0,
    val first: TestPart? = null,
    val second: TestPart? = null,
    val note: String? = null,
) {
    enum class Status { Running, Done, Stopped, Failed }

    /** Whether [part] writes the MP4. */
    val withMp4: Boolean get() = part == 1

    /** Plain `key=value` lines, read back by [decode]. */
    fun encode(): String = buildString {
        appendLine("status=${status.name}")
        appendLine("part=$part")
        appendLine("ends=$partEndsAtMillis")
        first?.let { appendLine("first=${it.fps},${it.skippedPercent}") }
        second?.let { appendLine("second=${it.fps},${it.skippedPercent}") }
        note?.let { appendLine("note=${it.replace('\n', ' ')}") }
    }

    companion object {
        const val Parts = CameraTestText.PARTS
        const val PartMinutes = CameraTestText.PART_MINUTES
        private const val FileName = "camera-test.txt"

        fun file(context: Context) = File(context.filesDir, FileName)

        /** The state [encode] wrote; null for no state or one this version cannot read. */
        fun decode(text: String): CameraTestState? {
            val values = text.lines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }.toMap()
            val status = Status.entries.firstOrNull { it.name == values["status"] }
            val part = values["part"]?.toIntOrNull()
            return if (status == null || part == null) {
                null
            } else {
                CameraTestState(
                    status,
                    part,
                    values["ends"]?.toLongOrNull() ?: 0,
                    values["first"]?.let(::testPart),
                    values["second"]?.let(::testPart),
                    values["note"],
                )
            }
        }

        private fun testPart(text: String): TestPart? {
            val numbers = text.split(',').map { it.toDoubleOrNull() }
            val fps = numbers.getOrNull(0)
            val skipped = numbers.getOrNull(1)
            return if (numbers.size == 2 && fps != null && skipped != null) TestPart(fps, skipped) else null
        }

        fun read(context: Context): CameraTestState? =
            try {
                file(context).takeIf { it.exists() }?.readText()?.let(::decode)
            } catch (_: IOException) {
                null
            }

        /** Replaces the state at once (written aside, then renamed), so a reader never sees half of it. */
        fun write(context: Context, state: CameraTestState) {
            val target = file(context)
            val temp = File(target.parentFile, "$FileName.tmp")
            try {
                temp.writeText(state.encode())
                if (!temp.renameTo(target)) target.writeText(state.encode())
            } catch (_: IOException) {
                // The screen then shows the last state it read; the log still has every line.
            }
        }

        fun clear(context: Context) {
            file(context).delete()
        }
    }
}
