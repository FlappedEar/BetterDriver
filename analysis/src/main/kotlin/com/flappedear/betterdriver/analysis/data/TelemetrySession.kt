@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements") // Ported as written; Qt fuzzy-compare precision.

package com.flappedear.betterdriver.analysis.data

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.wrapLongitudeDegrees
import kotlin.math.abs
import kotlin.math.min

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/telemetry_session.dart and
// timing_gate.dart (Apache 2.0). Values are doubles here; Telemetry stores float32, so readers
// that must match it to the last bit (the parity tests) round values to float themselves.

/** How [TelemetryChannel.valueAt] reads between two samples. */
enum class Interpolation {
    /** The closer sample; the earlier one on a tie. */
    Nearest,

    /** The sample at or before the time. */
    Previous,

    /** Straight line between two finite neighbours. */
    Linear,

    /** [Linear] for a longitude in degrees, the short way round across ±180°. */
    Longitude,
}

/** One sample: time in seconds and its value. */
data class SamplePoint(val time: Double, val value: Double)

/**
 * One recorded quantity: strictly increasing, finite times in seconds and values where NaN means
 * no data. The arrays are owned by the channel; callers must not change them afterwards.
 */
class TelemetryChannel(
    val name: String,
    val timestamps: DoubleArray,
    val values: DoubleArray,
    val unit: String = "",
) {
    init {
        require(timestamps.size == values.size) {
            "channel \"$name\" has ${timestamps.size} timestamps and ${values.size} values"
        }
        var previous = Double.NEGATIVE_INFINITY
        for ((i, t) in timestamps.withIndex()) {
            require(t.isFinite() && t > previous) {
                "channel \"$name\" timestamp $i ($t) must be finite and after the one before"
            }
            previous = t
        }
    }

    /** The median positive interval between samples (the upper one of an even count), or 0. */
    val baseIntervalSeconds: Double = medianInterval(timestamps)

    val sampleCount: Int get() = timestamps.size

    /**
     * Longest interval between two samples that is still continuous data: three times the
     * median interval, and at least [minimumSeconds].
     */
    fun gapThreshold(minimumSeconds: Double = 0.0): Double {
        val floor = if (minimumSeconds > 0.0) minimumSeconds else 0.0
        val threshold = baseIntervalSeconds * 3.0
        return if (threshold > floor) threshold else floor
    }

    /**
     * The value at [time]. Null outside the channel's range and on missing samples; linear reading
     * needs two adjacent finite samples. Between two samples farther apart than [gapThreshold]
     * there is no value in any mode, so a loss of signal is never bridged.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    fun valueAt(time: Double, mode: Interpolation = Interpolation.Linear): Double? {
        if (timestamps.isEmpty() || !time.isFinite()) return null
        if (time < timestamps.first() || time > timestamps.last()) return null
        val next = lowerBound(timestamps, time)
        fun finiteAt(i: Int): Double? = values[i].takeIf { it.isFinite() }
        if (timestamps[next] == time) return finiteAt(next)
        if (next == 0) return null
        val previous = next - 1
        val gap = gapThreshold()
        if (gap > 0.0 && timestamps[next] - timestamps[previous] > gap) return null
        return when (mode) {
            Interpolation.Previous -> finiteAt(previous)
            Interpolation.Nearest ->
                if (time - timestamps[previous] <= timestamps[next] - time) finiteAt(previous) else finiteAt(next)
            Interpolation.Linear, Interpolation.Longitude -> {
                val span = timestamps[next] - timestamps[previous]
                val before = finiteAt(previous)
                val after = finiteAt(next)
                if (before == null || after == null || !span.isFinite() || span <= 0.0) return null
                val ratio = (time - timestamps[previous]) / span
                val difference = after - before
                val value = if (mode == Interpolation.Longitude && abs(difference) > 180.0) {
                    wrapLongitudeDegrees(before + wrapLongitudeDegrees(difference) * ratio)
                } else {
                    before + difference * ratio
                }
                value.takeIf { it.isFinite() }
            }
        }
    }

    companion object {
        @Suppress("ReturnCount")
        fun medianInterval(timestamps: DoubleArray): Double {
            if (timestamps.size < 2) return 0.0
            val intervals = DoubleArray(timestamps.size - 1)
            var count = 0
            for (i in 1 until timestamps.size) {
                val interval = timestamps[i] - timestamps[i - 1]
                if (interval.isFinite() && interval > 0.0) intervals[count++] = interval
            }
            if (count == 0) return 0.0
            val sorted = intervals.copyOf(count).also { it.sort() }
            return sorted[count / 2]
        }
    }
}

/** What a timing line in a recording is for. */
enum class TimingGateType { Start, Split, Unknown }

/** A timing line between two endpoints. */
data class TimingGate(
    val type: TimingGateType,
    val name: String,
    val endpointA: GeoCoordinate,
    val endpointB: GeoCoordinate,
)

/** Semantic channel names the analysis reads (Telemetry's aliases). */
object Aliases {
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val SPEED = "speed"
    const val LATERAL_ACCELERATION = "lateralAcceleration"
    const val LONGITUDINAL_ACCELERATION = "longitudinalAcceleration"
    const val THROTTLE = "throttle"
    const val BRAKE = "brake"
}

/**
 * One recording ready for analysis. Times are seconds from the first sample. [aliases] maps a
 * semantic name ([Aliases]) to a channel name.
 */
class TelemetrySession(
    val channels: Map<String, TelemetryChannel>,
    val aliases: Map<String, String>,
    val timingGates: List<TimingGate> = emptyList(),
) {
    /** The channel named [name], or the channel an alias of that name points to. */
    fun channel(name: String): TelemetryChannel? = channels[aliases[name] ?: name]

    fun valueAt(name: String, time: Double, mode: Interpolation = Interpolation.Linear): Double? =
        channel(name)?.valueAt(time, mode)

    /**
     * Actual samples of [name] between [rangeStart] and [rangeEnd] (either order), split at every
     * missing value and every interval longer than the gap threshold. Each of [maximumPoints] time
     * buckets contributes its ordered minimum and maximum; when that still exceeds twice
     * [maximumPoints], the overall extremes plus evenly spaced samples are kept.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun sampledSegments(
        name: String,
        rangeStart0: Double,
        rangeEnd0: Double,
        maximumPoints: Int,
    ): List<List<SamplePoint>> {
        if (!rangeStart0.isFinite() || !rangeEnd0.isFinite() || maximumPoints < 2) return emptyList()
        val rangeStart = min(rangeStart0, rangeEnd0)
        val rangeEnd = maxOf(rangeStart0, rangeEnd0)
        val span = rangeEnd - rangeStart
        if (!span.isFinite()) return emptyList()
        val found = channel(name) ?: return emptyList()
        if (found.timestamps.isEmpty()) return emptyList()

        val raw = mutableListOf<List<SamplePoint>>()
        var current = mutableListOf<SamplePoint>()
        val gap = found.gapThreshold()
        for (i in found.timestamps.indices) {
            val t = found.timestamps[i]
            val v = found.values[i]
            if (t < rangeStart || t > rangeEnd) continue
            if (!v.isFinite()) {
                if (current.isNotEmpty()) {
                    raw += current
                    current = mutableListOf()
                }
                continue
            }
            if (current.isNotEmpty() && gap > 0.0 && t - current.last().time > gap) {
                raw += current
                current = mutableListOf()
            }
            current += SamplePoint(t, v)
        }
        if (current.isNotEmpty()) raw += current
        if (raw.isEmpty()) return emptyList()
        if (span <= 0.0) return raw

        val result = mutableListOf<List<SamplePoint>>()
        for (segment in raw) {
            val reduced = mutableListOf<SamplePoint>()
            var active = -1
            var minimum = segment.first()
            var maximum = segment.first()
            fun flush() {
                if (active < 0) return
                if (minimum.time <= maximum.time) {
                    reduced += minimum
                    if (!qtFuzzyEqual(maximum, minimum)) reduced += maximum
                } else {
                    reduced += maximum
                    reduced += minimum
                }
            }
            for (p in segment) {
                val position = (p.time - rangeStart) / span * maximumPoints
                if (!position.isFinite()) return emptyList()
                val bucket = position.coerceIn(0.0, (maximumPoints - 1).toDouble()).toInt()
                if (bucket != active) {
                    flush()
                    active = bucket
                    minimum = p
                    maximum = p
                } else {
                    if (p.value < minimum.value) minimum = p
                    if (p.value > maximum.value) maximum = p
                }
            }
            flush()
            if (reduced.isNotEmpty()) result += reduced
        }
        val total = result.sumOf { it.size }
        val limit = maximumPoints * 2
        if (total <= limit) return result

        val segmentOf = mutableListOf<Int>()
        val candidates = mutableListOf<SamplePoint>()
        result.forEachIndexed { s, seg -> seg.forEach { segmentOf += s; candidates += it } }
        val selected = BooleanArray(candidates.size)
        var minIndex = 0
        var maxIndex = 0
        for (i in 1 until candidates.size) {
            if (candidates[i].value < candidates[minIndex].value) minIndex = i
            if (candidates[i].value > candidates[maxIndex].value) maxIndex = i
        }
        selected[minIndex] = true
        selected[maxIndex] = true
        val budget = if (limit - 2 > 1) limit - 2 else 1
        for (slot in 0 until budget) {
            val i = if (budget == 1) 0 else (slot.toLong() * (candidates.size - 1) / (budget - 1)).toInt()
            selected[i] = true
        }
        val bounded = mutableListOf<MutableList<SamplePoint>>()
        var previousSegment = -1
        for (i in candidates.indices) {
            if (!selected[i]) continue
            if (bounded.isEmpty() || segmentOf[i] != previousSegment) bounded += mutableListOf<SamplePoint>()
            bounded.last() += candidates[i]
            previousSegment = segmentOf[i]
        }
        return bounded
    }
}

private fun qtFuzzyEqual(a: SamplePoint, b: SamplePoint) =
    qtFuzzyEqual(a.time, b.time) && qtFuzzyEqual(a.value, b.value)

private fun qtFuzzyEqual(a: Double, b: Double): Boolean {
    if (a == 0.0 || b == 0.0) return abs(a - b) <= 0.000000000001
    return abs(a - b) * 1000000000000.0 <= min(abs(a), abs(b))
}

/** Index of the first element not less than [value] in ascending [list]. */
fun lowerBound(list: DoubleArray, value: Double, start: Int = 0, end: Int = list.size): Int {
    var low = start
    var high = end
    while (low < high) {
        val middle = low + ((high - low) shr 1)
        if (list[middle] < value) low = middle + 1 else high = middle
    }
    return low
}

/** Index of the first element greater than [value] in ascending [list]. */
fun upperBound(list: DoubleArray, value: Double, start: Int = 0, end: Int = list.size): Int {
    var low = start
    var high = end
    while (low < high) {
        val middle = low + ((high - low) shr 1)
        if (list[middle] <= value) low = middle + 1 else high = middle
    }
    return low
}
