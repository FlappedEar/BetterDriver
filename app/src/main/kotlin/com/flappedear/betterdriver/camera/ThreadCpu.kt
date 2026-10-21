package com.flappedear.betterdriver.camera

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale

/**
 * Which threads of this process used its CPU since the last line (FBBD-278). The camera process uses
 * about 60 % of a core while recording 1080p · 60 with only a VideoCapture bound (2026-10-21 15:55
 * log), and the camera still skips frames; this names the threads that time goes to, such as
 * CameraX's camera thread, an OpenGL thread or the audio and muxer threads.
 *
 * `camera threads: CameraX-core_camera_0=21.4% GLThread=18.0% ... exited=0.4%`: the [Top] busiest, in
 * percent of one core over the time since the last line, and the time of threads that ended in between
 * (the process's own total less what its live threads used). Main thread only.
 */
class ThreadCpu(private val self: File = File("/proc/self")) {
    private val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(DefaultTicks)
        .takeIf { it > 0 } ?: DefaultTicks
    private var last: Map<Int, Long> = emptyMap()
    private var lastTotal = 0L
    private var lastMillis = 0L

    /** The line for the time since the last call at [nowMillis]; null on the first call or with no time between. */
    fun line(nowMillis: Long): String? {
        val now = sample()
        val total = stat(self)?.second ?: 0L
        val before = last
        val totalBefore = lastTotal
        val wall = nowMillis - lastMillis
        val first = lastMillis == 0L
        last = now.mapValues { it.value.second }
        lastTotal = total
        lastMillis = nowMillis
        return if (first || wall <= 0) null else busiest(before, now, total - totalBefore, wall, ticksPerSecond)
    }

    private fun stat(dir: File) = runCatching { File(dir, "stat").readText() }.getOrNull()?.let(::threadTicks)

    private fun sample(): Map<Int, Pair<String, Long>> =
        File(self, "task").listFiles().orEmpty().mapNotNull { dir ->
            dir.name.toIntOrNull()?.let { tid -> stat(dir)?.let { tid to it } }
        }.toMap()

    companion object {
        /** Linux's usual clock tick, should sysconf not answer. */
        private const val DefaultTicks = 100L
        private const val Top = 6
        private const val Percent = 100.0
        private const val MillisPerSecond = 1_000.0

        /** The utime and stime fields' places after the thread name in /proc/.../stat. */
        private const val UtimeAfterName = 11
        private const val StimeAfterName = 12

        /**
         * A thread's name and its CPU ticks (user + system) from its /proc/.../stat text; null when
         * the text cannot be read. The name sits in parentheses and may hold spaces and parentheses.
         */
        internal fun threadTicks(stat: String): Pair<String, Long>? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close < open) return null
            val fields = stat.substring(close + 1).trim().split(' ')
            val user = fields.getOrNull(UtimeAfterName)?.toLongOrNull()
            val system = fields.getOrNull(StimeAfterName)?.toLongOrNull()
            return if (user == null || system == null) null else stat.substring(open + 1, close) to user + system
        }

        /**
         * The [Top] busiest thread names between two samples [wallMillis] apart, and the time of
         * threads that ended in between: the process's [totalTicks] less what its live threads used.
         * Threads sharing a name add up, and a thread new since [before] counts from zero.
         */
        internal fun busiest(
            before: Map<Int, Long>,
            now: Map<Int, Pair<String, Long>>,
            totalTicks: Long,
            wallMillis: Long,
            ticksPerSecond: Long,
        ): String {
            val byName = now.entries.groupBy({ it.value.first }) { (tid, thread) ->
                (thread.second - (before[tid] ?: 0L)).coerceAtLeast(0)
            }.mapValues { it.value.sum() }
            val percent = { ticks: Long -> ticks * MillisPerSecond / ticksPerSecond * Percent / wallMillis }
            val top = byName.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(Top)
            val text = top.joinToString(" ") { (name, ticks) ->
                String.format(Locale.ROOT, "%s=%.1f%%", name.replace(' ', '_'), percent(ticks))
            }
            val exited = (totalTicks - byName.values.sum()).coerceAtLeast(0)
            val live = text.ifEmpty { "none" }
            return String.format(Locale.ROOT, "camera threads: %s exited=%.1f%%", live, percent(exited))
        }
    }
}
