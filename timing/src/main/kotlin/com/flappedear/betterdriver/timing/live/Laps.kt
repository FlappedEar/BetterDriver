package com.flappedear.betterdriver.timing.live

/** Out lap: from session start or leaving the pits. In lap: into the pits. Only timed laps count for bests. */
enum class LapKind { Out, Timed, In }

/**
 * A finished lap (FBBD-58). Times are microseconds on the session clock. [sectorMicros] has one
 * entry per sector; an entry is null when a line was missed (GNSS gap) or the lap did not start
 * at the start/finish line.
 */
data class Lap(
    val number: Int,
    val kind: LapKind,
    val startMicros: Long,
    val endMicros: Long,
    val sectorMicros: List<Long?>,
) {
    val timeMicros: Long get() = endMicros - startMicros
    val timeMillis: Long get() = (timeMicros + MicrosPerMilli / 2) / MicrosPerMilli

    /** Every sector was timed. */
    val allSectors: Boolean get() = sectorMicros.all { it != null }
}

internal const val MicrosPerMilli = 1_000L
internal const val MicrosPerSecond = 1_000_000.0

/**
 * Lap list with bests (FBBD-58, FBBD-59). Best lap and best sectors come from timed laps only.
 * Gains are in microseconds, negative meaning faster than the comparison.
 */
class LapBook(val sectorCount: Int) {
    private val all = mutableListOf<Lap>()

    val laps: List<Lap> get() = all.toList()

    var best: Lap? = null
        private set

    private val bestSectorTimes = arrayOfNulls<Long>(sectorCount)

    /** Fastest time per sector over the session's timed laps. */
    val bestSectors: List<Long?> get() = bestSectorTimes.toList()

    /** Sum of the best sectors, once every sector has one. */
    val theoreticalBestMicros: Long?
        get() = if (bestSectorTimes.all { it != null }) bestSectorTimes.sumOf { it!! } else null

    /** Records [lap]; true when it is the new best lap. */
    fun add(lap: Lap): Boolean {
        require(lap.sectorMicros.size == sectorCount) {
            "lap has ${lap.sectorMicros.size} sectors, track has $sectorCount"
        }
        all += lap
        if (lap.kind != LapKind.Timed) return false
        lap.sectorMicros.forEachIndexed { i, t ->
            val b = bestSectorTimes[i]
            if (t != null && (b == null || t < b)) bestSectorTimes[i] = t
        }
        val previous = best
        val isBest = previous == null || lap.timeMicros < previous.timeMicros
        if (isBest) best = lap
        return isBest
    }

    /** Per-sector gain of [lap] against the best lap; null where either sector is missing. */
    fun gainVsBestLap(lap: Lap): List<Long?> = gains(lap.sectorMicros, best?.sectorMicros)

    /** Per-sector gain of [lap] against the best time for each sector. */
    fun gainVsBestSectors(lap: Lap): List<Long?> = gains(lap.sectorMicros, bestSectors)

    private fun gains(times: List<Long?>, against: List<Long?>?): List<Long?> =
        times.mapIndexed { i, t ->
            val b = against?.getOrNull(i)
            if (t == null || b == null) null else t - b
        }
}
