package com.flappedear.betterdriver.replay

import android.os.Debug
import kotlin.math.sqrt

/**
 * How fast this emulator's CPU is, for the CPU budget (FBBD-461): GitHub's runners are not all equally
 * fast, and on a slow one every process of the replay drive used about 1.9 times the CPU time for the
 * same work (same allocations and GC count), so the budget failed on code that passed on re-run.
 *
 * A fixed workload, timed in this thread's CPU time as the load lines time each process, outside the
 * recording so it is not in them. [millis] is the median of [Rounds] rounds after [WarmUps] warm-ups,
 * so the JIT has compiled it and one preempted round does not count.
 */
object CpuSpeed {
    fun millis(): Double {
        val data = DoubleArray(Size) { i -> ((i * Mul + Add) % Mod).toDouble() / Mod }
        repeat(WarmUps) { round(data) }
        val times = List(Rounds) {
            val start = Debug.threadCpuTimeNanos()
            sink += round(data)
            (Debug.threadCpuTimeNanos() - start) / NanosPerMilli
        }
        return times.sorted()[Rounds / 2]
    }

    /** Arithmetic and array reads and writes, no allocation: CPU only. */
    private fun round(data: DoubleArray): Double {
        var sum = 0.0
        repeat(Passes) { pass ->
            for (i in data.indices) {
                val v = data[i]
                val next = sqrt(v * v + pass) * Damp + v * (1 - Damp)
                data[i] = next - next.toLong()
                sum += next
            }
        }
        return sum
    }

    /** Keeps the result alive so the work is not optimised away. */
    @Volatile
    private var sink = 0.0

    private const val Size = 1 shl 16
    private const val Passes = 40
    private const val WarmUps = 10
    private const val Rounds = 7
    private const val Mul = 2_654_435_761L
    private const val Add = 12_345L
    private const val Mod = 1_000_003L
    private const val Damp = 0.5
    private const val NanosPerMilli = 1_000_000.0
}
