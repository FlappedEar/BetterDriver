package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.roundToLong

/** One GNSS position on the session clock. */
data class Fix(val tMicros: Long, val lat: Double, val lon: Double)

/** What a fix caused, in time order. */
sealed interface LapEvent {
    val tMicros: Long

    /** Sector [index] of lap [lapNumber] ended; [sectorMicros] is null if its start was missed. */
    data class SectorDone(
        override val tMicros: Long,
        val lapNumber: Int,
        val index: Int,
        val sectorMicros: Long?,
    ) : LapEvent

    data class LapDone(override val tMicros: Long, val lap: Lap) : LapEvent
}

/** Shortest lap counted, so GNSS noise at the line can never make a lap of a few seconds. */
const val DefaultMinLapSeconds = 10.0

/**
 * Lap state machine (FBBD-58) on line crossings (FBBD-57). Laps run from start/finish to
 * start/finish; the first lap of a session starts with the first fix and is an out lap.
 *
 * Lap kinds:
 * - A lap that crosses the pit entry line is an in lap.
 * - Otherwise a lap that crosses the pit exit line is an out lap.
 * - Without a pit exit line, the lap after an in lap is an out lap (pit lanes that cross the
 *   start/finish line). With one, it is timed (pit lanes that bypass the line: the in lap then
 *   holds the stop and the way out).
 * - Every other lap is timed.
 *
 * Feed fixes in time order from one thread. Fixes that go back in time are ignored.
 */
class LapTimer(private val track: Track, minLapSeconds: Double = DefaultMinLapSeconds) {
    private val startFinishLine = requireNotNull(track.startFinish) { "track ${track.id} has no start/finish line" }

    /** The frame every position is projected into; centred on the start/finish line. */
    val frame = LocalFrame(startFinishLine.left)
    private val startFinish = Gate(startFinishLine, frame)
    private val sectorGates = track.sectorLines.map { Gate(it, frame) }
    private val pitEntry = track.pitEntry?.let { Gate(it, frame) }
    private val pitExit = track.pitExit?.let { Gate(it, frame) }
    private val minLapMicros = (minLapSeconds * MicrosPerSecond).roundToLong()

    val sectorCount = track.sectorCount

    private var prev: Xy? = null
    private var prevT = 0L

    /** Number of the lap being driven; 0 before the first fix. */
    var lapNumber = 0
        private set

    /** Start of the lap being driven, on the session clock. */
    var lapStartMicros = 0L
        private set

    /** The lap being driven started at the start/finish line (not at session start). */
    var lapStartedAtLine = false
        private set

    /** Index of the sector being driven. */
    var currentSector = 0
        private set

    /** Where the lap being driven started, in [frame]: the start/finish crossing point. */
    var lapStartPoint: Xy? = null
        private set

    // Boundary k is the start of sector k; boundary sectorCount is the finish.
    private val boundaries = arrayOfNulls<Long>(sectorCount + 1)
    private var enteredPit = false
    private var exitedPit = false
    private var previousKind: LapKind? = null

    /** Processes one fix; returns what it caused, in time order. */
    @Suppress("ReturnCount") // First fix, out-of-order fix and no crossing each end early.
    fun update(fix: Fix): List<LapEvent> {
        val p = frame.toXy(fix.lat, fix.lon)
        val p0 = prev
        if (p0 == null) {
            prev = p
            prevT = fix.tMicros
            lapNumber = 1
            lapStartMicros = fix.tMicros
            return emptyList()
        }
        if (fix.tMicros <= prevT) return emptyList()
        val crossings = buildList {
            startFinish.crossing(p0, prevT, p, fix.tMicros)?.let { add(Hit(it, HitKind.StartFinish, 0)) }
            sectorGates.forEachIndexed { i, g ->
                g.crossing(p0, prevT, p, fix.tMicros)?.let { add(Hit(it, HitKind.Sector, i)) }
            }
            pitEntry?.crossing(p0, prevT, p, fix.tMicros)?.let { add(Hit(it, HitKind.PitEntry, 0)) }
            pitExit?.crossing(p0, prevT, p, fix.tMicros)?.let { add(Hit(it, HitKind.PitExit, 0)) }
        }.sortedBy { it.crossing.tMicros }
        prev = p
        prevT = fix.tMicros
        if (crossings.isEmpty()) return emptyList()
        val events = mutableListOf<LapEvent>()
        for (hit in crossings) {
            val t = hit.crossing.tMicros.roundToLong()
            when (hit.kind) {
                HitKind.StartFinish -> onStartFinish(t, hit.crossing.at, events)
                HitKind.Sector -> onSectorLine(hit.index, t, events)
                HitKind.PitEntry -> enteredPit = true
                HitKind.PitExit -> exitedPit = true
            }
        }
        return events
    }

    private fun onStartFinish(t: Long, at: Xy, events: MutableList<LapEvent>) {
        if (lapStartedAtLine && t - lapStartMicros < minLapMicros) return
        boundaries[sectorCount] = t
        val last = sectorCount - 1
        events += LapEvent.SectorDone(t, lapNumber, last, sectorTime(last))
        val lap = Lap(lapNumber, kindOfLapEnding(), lapStartMicros, t, List(sectorCount, ::sectorTime))
        events += LapEvent.LapDone(t, lap)
        previousKind = lap.kind
        lapNumber++
        lapStartMicros = t
        lapStartedAtLine = true
        lapStartPoint = at
        currentSector = 0
        boundaries.fill(null)
        boundaries[0] = t
        enteredPit = false
        exitedPit = false
    }

    /** Sector line [i] ends sector i. Lines must come in order; skipped ones leave their sectors untimed. */
    private fun onSectorLine(i: Int, t: Long, events: MutableList<LapEvent>) {
        if (i < currentSector) return
        boundaries[i + 1] = t
        events += LapEvent.SectorDone(t, lapNumber, i, sectorTime(i))
        currentSector = i + 1
    }

    private fun sectorTime(i: Int): Long? {
        val start = boundaries[i]
        val end = boundaries[i + 1]
        return if (start == null || end == null) null else end - start
    }

    private fun kindOfLapEnding(): LapKind = when {
        !lapStartedAtLine -> LapKind.Out
        enteredPit -> LapKind.In
        exitedPit -> LapKind.Out
        previousKind == LapKind.In && pitExit == null -> LapKind.Out
        else -> LapKind.Timed
    }

    private enum class HitKind { StartFinish, Sector, PitEntry, PitExit }

    private data class Hit(val crossing: Crossing, val kind: HitKind, val index: Int)
}
