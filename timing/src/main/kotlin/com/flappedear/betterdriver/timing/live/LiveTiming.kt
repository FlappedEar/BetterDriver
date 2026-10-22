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
    /** The track is point to point (FBBD-155): laps are runs, and [lapMillis] is null between runs. */
    val pointToPoint: Boolean = false,
) {
    companion object {
        fun empty(sectorCount: Int, pointToPoint: Boolean = false) = LiveTimingState(
            lapNumber = 0, lapMillis = null, deltaSeconds = null, predictedMillis = null,
            lastMillis = null, bestMillis = null, theoreticalBestMillis = null,
            sectors = List(sectorCount) { LiveSector(sectorName(it), null, null, current = it == 0 && !pointToPoint) },
            pointToPoint = pointToPoint,
        )
    }
}

fun sectorName(index: Int) = "S${index + 1}"

/**
 * Live lap timer, sector times and predictive delta for one session on one track (FBBD-58 to
 * FBBD-60). Pure and single-threaded: the app feeds it fixes from the recorder's listener thread
 * and shows [state]. Replaying a recorded session through it gives the same laps, so it also
 * re-times a session after its lines are edited (FBBD-56).
 *
 * On a point-to-point track (FBBD-155) each run is a lap: the delta runs against the best run, and
 * between runs there is no lap time or delta while the last run's sectors stay on the strip.
 */
class LiveTiming(track: Track, minLapSeconds: Double = defaultMinLapSeconds(track)) {
    private val timer = LapTimer(track, minLapSeconds)
    val book = LapBook(timer.sectorCount)

    private var reference: DeltaReference? = null
    private var cursor: DeltaReference.Cursor? = null

    // Path of the lap being driven after its start crossing ([pathStart]), for use as the next reference.
    private val pathPoints = mutableListOf<Xy>()
    private val pathTimes = mutableListOf<Long>()

    // Sector results of the lap being driven, compared with the bests as they were when each sector ended.
    private val vsBestLap = arrayOfNulls<Long>(timer.sectorCount)
    private val vsBestSector = arrayOfNulls<Long>(timer.sectorCount)

    var state: LiveTimingState = LiveTimingState.empty(timer.sectorCount, timer.pointToPoint)
        private set

    /** Takes [fix]; one the timer ignores (older than, or as old as, the last one) changes nothing (audit A12). */
    fun update(fix: Fix): LiveTimingState {
        if (!timer.accepts(fix)) return state
        val events = timer.update(fix)
        for (e in events) {
            when (e) {
                is LapEvent.SectorDone -> onSectorDone(e)
                is LapEvent.LapDone -> onLapDone(e)
                is LapEvent.RunStarted -> onRunStarted()
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

    private fun onLapDone(e: LapEvent.LapDone) {
        val lap = e.lap
        val newBest = book.add(lap)
        val start = pathStart
        val end = e.endPoint
        if (newBest && start != null && end != null) {
            // The lap's path runs from its start crossing to its end crossing; both ends are on a line.
            reference = DeltaReference(listOf(start) + pathPoints + end, listOf(0L) + pathTimes + lap.timeMicros)
        }
        // On a circuit the next lap starts where this one ended; a point-to-point run at [onRunStarted].
        pathStart = timer.lapStartPoint
        pathPoints.clear()
        pathTimes.clear()
        heldVsBestLap = vsBestLap.copyOf()
        heldVsBestSector = vsBestSector.copyOf()
        holdUntilMicros = lap.endMicros + SectorHoldMicros
        vsBestLap.fill(null)
        vsBestSector.fill(null)
        cursor = reference?.Cursor()
    }

    /** A point-to-point run started: its path, sectors and delta start afresh, also after an aborted run. */
    private fun onRunStarted() {
        pathStart = timer.lapStartPoint
        pathPoints.clear()
        pathTimes.clear()
        vsBestLap.fill(null)
        vsBestSector.fill(null)
        holdUntilMicros = Long.MIN_VALUE
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
            lapMillis = if (timer.running) lapMicros / MicrosPerMilli else null,
            deltaSeconds = delta?.let { it / MicrosPerSecond },
            predictedMillis = predicted,
            lastMillis = book.laps.lastOrNull()?.timeMillis,
            bestMillis = best?.timeMillis,
            theoreticalBestMillis = book.theoreticalBestMicros?.let { (it + MicrosPerMilli / 2) / MicrosPerMilli },
            sectors = sectors(now),
            pointToPoint = timer.pointToPoint,
        )
    }

    private fun sectors(now: Long): List<LiveSector> {
        // Between point-to-point runs the last run's sectors stay until the next run starts.
        val holding = timer.currentSector == 0 && (now < holdUntilMicros || !timer.running)
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
fun timeSession(
    track: Track,
    fixes: Sequence<Fix>,
    minLapSeconds: Double = defaultMinLapSeconds(track),
): LapBook {
    val live = LiveTiming(track, minLapSeconds)
    fixes.forEach { live.update(it) }
    return live.book
}
