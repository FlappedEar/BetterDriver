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

    /** A lap or run ended; [endPoint] is where it crossed the line, in the timer's frame. */
    data class LapDone(override val tMicros: Long, val lap: Lap, val endPoint: Xy? = null) : LapEvent

    /**
     * Run [lapNumber] of a point-to-point track started at the start line (FBBD-155). Circuits have
     * no such event: each lap starts where the one before ended.
     */
    data class RunStarted(override val tMicros: Long, val lapNumber: Int) : LapEvent
}

/** Shortest lap counted, so GNSS noise at the line can never make a lap of a few seconds. */
const val DefaultMinLapSeconds = 10.0

/**
 * Shortest point-to-point run counted (FBBD-155). Start and finish are different lines, so noise at
 * one line cannot end a run; this only keeps a sprint of a few seconds.
 */
const val DefaultMinRunSeconds = 2.0

/** The shortest lap counted on [track]: [DefaultMinRunSeconds] point to point, else [DefaultMinLapSeconds]. */
fun defaultMinLapSeconds(track: Track): Double = if (track.pointToPoint) DefaultMinRunSeconds else DefaultMinLapSeconds

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
 * On a point-to-point track (FBBD-155) a run starts at a forward crossing of the start line and
 * ends at a forward crossing of the finish line; it is a timed lap numbered from 1. Sector lines
 * count only during a run, pit lines not at all, and nothing is timed between runs (the drive back
 * to the start, which crosses the lines the other way). Crossing the start line again during a run
 * starts it over: the run was aborted and the car came back round, so it keeps its number.
 *
 * Feed fixes in time order from one thread. Fixes that go back in time are ignored.
 */
class LapTimer(private val track: Track, minLapSeconds: Double = defaultMinLapSeconds(track)) {
    private val startFinishLine = requireNotNull(track.startFinish) { "track ${track.id} has no start/finish line" }

    /** Runs from a start line to a finish line instead of laps. */
    val pointToPoint: Boolean = track.pointToPoint

    /** The frame every position is projected into; centred on the start/finish line. */
    val frame = LocalFrame(startFinishLine.left)
    private val startFinish = Gate(startFinishLine, frame)
    private val finish = if (pointToPoint) {
        Gate(requireNotNull(track.finish) { "point-to-point track ${track.id} has no finish line" }, frame)
    } else {
        null
    }
    private val sectorGates = track.sectorLines.map { Gate(it, frame) }
    private val pitEntry = track.pitEntry?.takeUnless { pointToPoint }?.let { Gate(it, frame) }
    private val pitExit = track.pitExit?.takeUnless { pointToPoint }?.let { Gate(it, frame) }
    private val minLapMicros = (minLapSeconds * MicrosPerSecond).roundToLong()

    val sectorCount = track.sectorCount

    private var prev: Xy? = null
    private var prevT = 0L

    /**
     * Number of the lap being driven; 0 before the first fix. On a point-to-point track, the run
     * being driven or, between runs, the last one; 0 before the first run.
     */
    var lapNumber = 0
        private set

    /** Start of the lap being driven, on the session clock. */
    var lapStartMicros = 0L
        private set

    /**
     * The lap being driven started at the start/finish line (not at session start). On a
     * point-to-point track: a run is on.
     */
    var lapStartedAtLine = false
        private set

    /** A lap is being timed: always on a circuit, only during a run on a point-to-point track. */
    val running: Boolean get() = !pointToPoint || lapStartedAtLine

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
            if (!pointToPoint) lapNumber = 1
            lapStartMicros = fix.tMicros
            return emptyList()
        }
        if (fix.tMicros <= prevT) return emptyList()
        val crossings = crossings(p0, p, fix.tMicros)
        prev = p
        prevT = fix.tMicros
        if (crossings.isEmpty()) return emptyList()
        val events = mutableListOf<LapEvent>()
        for (hit in crossings) {
            val t = hit.crossing.tMicros.roundToLong()
            val at = hit.crossing.at
            when (hit.kind) {
                HitKind.StartFinish -> if (pointToPoint) onRunStart(t, at, events) else onStartFinish(t, at, events)
                HitKind.Finish -> onFinish(t, at, events)
                HitKind.Sector -> if (running) onSectorLine(hit.index, t, events)
                HitKind.PitEntry -> enteredPit = true
                HitKind.PitExit -> exitedPit = true
            }
        }
        return events
    }

    /** Every line crossed between the last fix ([p0]) and this one, in time order. */
    private fun crossings(p0: Xy, p: Xy, t: Long): List<Hit> = buildList {
        startFinish.crossing(p0, prevT, p, t)?.let { add(Hit(it, HitKind.StartFinish, 0)) }
        finish?.crossing(p0, prevT, p, t)?.let { add(Hit(it, HitKind.Finish, 0)) }
        sectorGates.forEachIndexed { i, g ->
            g.crossing(p0, prevT, p, t)?.let { add(Hit(it, HitKind.Sector, i)) }
        }
        pitEntry?.crossing(p0, prevT, p, t)?.let { add(Hit(it, HitKind.PitEntry, 0)) }
        pitExit?.crossing(p0, prevT, p, t)?.let { add(Hit(it, HitKind.PitExit, 0)) }
    }.sortedBy { it.crossing.tMicros }

    private fun onStartFinish(t: Long, at: Xy, events: MutableList<LapEvent>) {
        if (lapStartedAtLine && t - lapStartMicros < minLapMicros) return
        val (lap, lastSector) = endLap(t, kindOfLapEnding())
        events += lastSector
        events += LapEvent.LapDone(t, lap, at)
        previousKind = lap.kind
        lapNumber++
        startLap(t, at)
        enteredPit = false
        exitedPit = false
    }

    /** A forward crossing of a point-to-point track's start line: a run starts, or starts over. */
    private fun onRunStart(t: Long, at: Xy, events: MutableList<LapEvent>) {
        if (!lapStartedAtLine) lapNumber++
        startLap(t, at)
        events += LapEvent.RunStarted(t, lapNumber)
    }

    /** A forward crossing of the finish line ends the run, if one is on. */
    private fun onFinish(t: Long, at: Xy, events: MutableList<LapEvent>) {
        if (!lapStartedAtLine || t - lapStartMicros < minLapMicros) return
        val (lap, lastSector) = endLap(t, LapKind.Timed)
        events += lastSector
        events += LapEvent.LapDone(t, lap, at)
        lapStartedAtLine = false
        lapStartPoint = null
        currentSector = 0
        boundaries.fill(null)
    }

    /** The lap being driven, ended at [t], and the event for its last sector. */
    private fun endLap(t: Long, kind: LapKind): Pair<Lap, LapEvent.SectorDone> {
        boundaries[sectorCount] = t
        val last = sectorCount - 1
        val sector = LapEvent.SectorDone(t, lapNumber, last, sectorTime(last))
        return Lap(lapNumber, kind, lapStartMicros, t, List(sectorCount, ::sectorTime)) to sector
    }

    private fun startLap(t: Long, at: Xy) {
        lapStartMicros = t
        lapStartedAtLine = true
        lapStartPoint = at
        currentSector = 0
        boundaries.fill(null)
        boundaries[0] = t
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

    private enum class HitKind { StartFinish, Finish, Sector, PitEntry, PitExit }

    private data class Hit(val crossing: Crossing, val kind: HitKind, val index: Int)
}
