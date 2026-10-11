package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.roundToLong

/** One sector for the track screen's strip. Deltas are seconds, negative meaning faster. */
data class LiveSector(
    val name: String,
    /** Against the same sector of the session's best lap; null until the sector is done or with no best lap. */
    val deltaVsBestLapSeconds: Double?,
    /** Against the session's best time for this sector. */
    val deltaVsBestSectorSeconds: Double?,
    val current: Boolean,
)

/** Everything live timing shows (FBBD-12). Null means "not known yet". */
data class LiveTimingState(
    val lapNumber: Int,
    val lapMillis: Long?,
    /** Predictive delta to the session's best lap; negative is ahead. */
    val deltaSeconds: Double?,
    val predictedMillis: Long?,
    val lastMillis: Long?,
    val bestMillis: Long?,
    val theoreticalBestMillis: Long?,
    val sectors: List<LiveSector>,
) {
    companion object {
        fun empty(sectorCount: Int) = LiveTimingState(
            lapNumber = 0, lapMillis = null, deltaSeconds = null, predictedMillis = null,
            lastMillis = null, bestMillis = null, theoreticalBestMillis = null,
            sectors = List(sectorCount) { LiveSector(sectorName(it), null, null, current = it == 0) },
        )
    }
}

fun sectorName(index: Int) = "S${index + 1}"

/**
 * Live lap timer, sector times and predictive delta for one session on one track (FBBD-58 to
 * FBBD-60). Pure and single-threaded: the app feeds it fixes from the recorder's listener thread
 * and shows [state]. Replaying a recorded session through it gives the same laps, so it also
 * re-times a session after its lines are edited (FBBD-56).
 */
class LiveTiming(track: Track, minLapSeconds: Double = DefaultMinLapSeconds) {
    private val timer = LapTimer(track, minLapSeconds)
    val book = LapBook(timer.sectorCount)

    private var reference: DeltaReference? = null
    private var cursor: DeltaReference.Cursor? = null

    // Path of the lap being driven, from its start/finish crossing, for use as the next reference.
    private val pathPoints = mutableListOf<Xy>()
    private val pathTimes = mutableListOf<Long>()

    // Sector results of the lap being driven, compared with the bests as they were when each sector ended.
    private val vsBestLap = arrayOfNulls<Long>(timer.sectorCount)
    private val vsBestSector = arrayOfNulls<Long>(timer.sectorCount)

    var state: LiveTimingState = LiveTimingState.empty(timer.sectorCount)
        private set

    fun update(fix: Fix): LiveTimingState {
        val events = timer.update(fix)
        for (e in events) {
            when (e) {
                is LapEvent.SectorDone -> onSectorDone(e)
                is LapEvent.LapDone -> onLapDone(e.lap)
            }
        }
        val p = timer.frame.toXy(fix.lat, fix.lon)
        if (timer.lapStartedAtLine && fix.tMicros > timer.lapStartMicros) {
            pathPoints += p
            pathTimes += fix.tMicros - timer.lapStartMicros
        }
        state = buildState(fix.tMicros, p)
        return state
    }

    private fun onSectorDone(e: LapEvent.SectorDone) {
        val t = e.sectorMicros
        vsBestLap[e.index] = t?.let { s -> book.best?.sectorMicros?.get(e.index)?.let { s - it } }
        vsBestSector[e.index] = t?.let { s -> book.bestSectors[e.index]?.let { s - it } }
    }

    private fun onLapDone(lap: Lap) {
        val newBest = book.add(lap)
        val start = timer.lapStartPoint
        if (newBest && start != null) {
            // The lap's path runs from the previous crossing to this one; both ends are on the line.
            val previousStart = pathStart
            if (previousStart != null) {
                reference = DeltaReference(
                    listOf(previousStart) + pathPoints + start,
                    listOf(0L) + pathTimes + lap.timeMicros,
                )
            }
        }
        pathStart = start
        pathPoints.clear()
        pathTimes.clear()
        heldVsBestLap = vsBestLap.copyOf()
        heldVsBestSector = vsBestSector.copyOf()
        holdUntilMicros = lap.endMicros + SectorHoldMicros
        vsBestLap.fill(null)
        vsBestSector.fill(null)
        cursor = reference?.Cursor()
    }

    private var pathStart: Xy? = null

    // The finished lap's sectors stay on the strip for a few seconds after the line.
    private var heldVsBestLap = arrayOfNulls<Long>(timer.sectorCount)
    private var heldVsBestSector = arrayOfNulls<Long>(timer.sectorCount)
    private var holdUntilMicros = Long.MIN_VALUE

    private fun buildState(now: Long, p: Xy): LiveTimingState {
        val lapMicros = now - timer.lapStartMicros
        val best = book.best
        // The reference is the best lap, so both exist together; a lap from session start has no delta.
        val delta = if (timer.lapStartedAtLine) cursor?.referenceMicros(p)?.let { lapMicros - it } else null
        val predicted = best?.let { b -> delta?.let { ((b.timeMicros + it) / MicrosPerMilli).roundToLong() } }
        return LiveTimingState(
            lapNumber = timer.lapNumber,
            lapMillis = lapMicros / MicrosPerMilli,
            deltaSeconds = delta?.let { it / MicrosPerSecond },
            predictedMillis = predicted,
            lastMillis = book.laps.lastOrNull()?.timeMillis,
            bestMillis = best?.timeMillis,
            theoreticalBestMillis = book.theoreticalBestMicros?.let { (it + MicrosPerMilli / 2) / MicrosPerMilli },
            sectors = sectors(now),
        )
    }

    private fun sectors(now: Long): List<LiveSector> {
        val holding = now < holdUntilMicros && timer.currentSector == 0
        val lapSectors = if (holding) heldVsBestLap else vsBestLap
        val bestSectors = if (holding) heldVsBestSector else vsBestSector
        return List(timer.sectorCount) { i ->
            LiveSector(
                name = sectorName(i),
                deltaVsBestLapSeconds = lapSectors[i]?.let { it / MicrosPerSecond },
                deltaVsBestSectorSeconds = bestSectors[i]?.let { it / MicrosPerSecond },
                current = !holding && i == timer.currentSector,
            )
        }
    }
}

/** How long a finished lap's sector results stay on the strip after the line. */
private const val SectorHoldMicros = 5_000_000L

/** Times a whole recorded session, for review and after editing lines (FBBD-56). */
fun timeSession(track: Track, fixes: Sequence<Fix>, minLapSeconds: Double = DefaultMinLapSeconds): LapBook {
    val live = LiveTiming(track, minLapSeconds)
    fixes.forEach { live.update(it) }
    return live.book
}
