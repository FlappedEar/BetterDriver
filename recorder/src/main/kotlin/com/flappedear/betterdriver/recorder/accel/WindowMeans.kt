package com.flappedear.betterdriver.recorder.accel

/**
 * Means of one reading's finite values in windows around many times at once. Running sums are read
 * between two indices that only move forward, so a whole session of windows costs one pass over the
 * reading, where a search and a walk per window cost a pass per window. The lag searches read every
 * window up to 61 times, and a phone records about 110 samples a second: done window by window, the
 * analysis of a half-hour drive took many seconds on the phone (0.3.19).
 */
class WindowMeans(private val times: DoubleArray, values: DoubleArray) {
    private val sums = DoubleArray(times.size + 1)
    private val counts = IntArray(times.size + 1)

    init {
        require(times.size == values.size) { "${times.size} times and ${values.size} values" }
        for (i in times.indices) {
            val v = values[i]
            val finite = v.isFinite()
            sums[i + 1] = sums[i] + if (finite) v else 0.0
            counts[i + 1] = counts[i] + if (finite) 1 else 0
        }
    }

    /**
     * The mean of the finite values with times within [half] of each of [at] plus [shift], both ends
     * included; NaN where there is none. Fastest with [at] in ascending order, as the callers pass it.
     */
    fun at(at: DoubleArray, half: Double, shift: Double = 0.0): DoubleArray {
        val means = DoubleArray(at.size)
        var lo = 0
        var hi = 0
        var previous = Double.NEGATIVE_INFINITY
        for (j in at.indices) {
            val t = at[j] + shift
            if (!t.isFinite()) {
                means[j] = Double.NaN
                continue
            }
            // Out of order: start the indices again rather than give a wrong window.
            if (t < previous) {
                lo = 0
                hi = 0
            }
            previous = t
            val from = t - half
            val to = t + half
            while (lo < times.size && times[lo] < from) lo++
            if (hi < lo) hi = lo
            while (hi < times.size && times[hi] <= to) hi++
            val n = counts[hi] - counts[lo]
            means[j] = if (n > 0) (sums[hi] - sums[lo]) / n else Double.NaN
        }
        return means
    }
}
