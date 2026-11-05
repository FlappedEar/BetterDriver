package com.flappedear.betterdriver.replay

import android.os.Debug

/**
 * How fast this emulator runs the app's own code, for the CPU budget (FBBD-461): GitHub's runners are
 * not all equally fast, and on a slow one every process of the replay drive used about 1.9 times the
 * CPU time for the same work (same allocations and GC count), so the budget failed on code that passed
 * on re-run.
 *
 * [millis] times a fixed piece of app work in this thread's CPU time, as the load lines time each
 * process, outside the recording so it is not in them: the median of [Rounds] rounds after [WarmUps]
 * warm-ups, so the JIT has compiled it and one preempted round does not count. App code rather than a
 * synthetic loop: a square-root loop ran 2.1 times slower on an AMD EPYC 7763 runner than on a 9V45,
 * while the app's processes there used 1.0 to 1.3 times the CPU.
 */
object CpuSpeed {
    fun millis(work: () -> Unit): Double {
        repeat(WarmUps) { work() }
        val times = List(Rounds) {
            val start = Debug.threadCpuTimeNanos()
            work()
            (Debug.threadCpuTimeNanos() - start) / NanosPerMilli
        }
        return times.sorted()[Rounds / 2]
    }

    private const val WarmUps = 2
    private const val Rounds = 5
    private const val NanosPerMilli = 1_000_000.0
}
