package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A pass at least this much slower than the target over the corner window is worth a cue. */
const val MinLossMicros = 50_000L

/** Braking points closer than this to the target's are the same. */
const val BrakeToleranceMeters = 8.0

/** Minimum speeds closer than this to the target's are the same. */
const val SpeedToleranceKmh = 3.0

/** Never ask for more than this much later braking in one cue. */
const val MaxBrakeLaterMeters = 20.0

/** Never ask for more than this much more apex speed in one cue. */
const val MaxCarryKmh = 10.0

/** Corners with cues at [Verbosity.Focus]. */
const val FocusCorners = 3

/** Time from deciding to speak to the start of the sound, before any audio delay setting. */
const val DefaultLatencyMicros = 300_000L

/** Timed laps before corner cues when the track was driven earlier the same day (FBBD-190): a warm-up lap. */
const val WarmUpLaps = 1

/** A cue starts at most this long before it must have ended, so it is still fresh at the corner. */
private const val LeadMicros = 1_500_000L

/** A cue that would end less than this before the braking zone is not spoken at all. */
private const val MarginMicros = 300_000L

/** After a corner's apex the car is on the straight once it is this far past it. */
private const val ClearOfCornerMeters = 30.0

/** The car is braking when it slows faster than this. */
private const val BrakingMps2 = 2.5

/** Deceleration is measured over this long. */
private const val DecelWindowMicros = 400_000L

/** Below this speed (pit lane, spin) no cue is spoken. */
private const val MinCueSpeedKmh = 20.0

/** A lap time not spoken this long after the line is dropped. */
private const val LapCueExpiryMicros = 20_000_000L

/** A praise not said this long after its corner is dropped (FBBD-243). */
private const val PraiseExpiryMicros = 15_000_000L

/** [CornerBook.cuedOnLap] of a corner not cued yet: far enough below any lap that it is always rested. */
private const val NeverCued = Int.MIN_VALUE / 2

/** Losses kept per corner for picking focus corners. */
private const val LossHistory = 3

/** A cue's target has been driven this many times at least (FBBD-177): never a time seen once. */
const val ConfirmingPasses = 2

private const val MetresStep = 5
private const val KmhPerMps = 3.6
private const val MicrosPerSecond = 1_000_000.0

/** What one fix caused: cues to speak now and results of earlier cues. */
data class CoachUpdate(val cues: List<Cue>, val results: List<CueResult>) {
    companion object {
        val None = CoachUpdate(emptyList(), emptyList())
    }
}

/**
 * Per-corner state of one session (FBBD-97): each corner's target (the fastest pass so far), its last
 * pass, recent losses against the target, and the lap it was last cued on. [chosen] are the corners
 * of the session focus (FBBD-20) and those the driver picked in the lap analysis (FBBD-148).
 * [stoppedBefore] has, for each corner the lap that set the corners came to from a stop, where it
 * stopped (FBBD-202).
 *
 * Cues aim at the [confirmed] target (FBBD-177): the second fastest pass, so what they ask for has
 * been done on two laps at least; a corner improved on one lap only gets no cue for it. Passes too
 * weak to coach from ([unusable]) are never targets.
 */
private class CornerBook(
    val map: CornerMap,
    initialTargets: List<CornerPass>,
    val chosen: Chosen,
    private val stoppedBefore: List<Double?> = emptyList(),
) {
    private val n = map.corners.size
    private val fastest = Array(n) { mutableListOf<CornerPass>() }
    val targets = arrayOfNulls<CornerPass>(n)
    val lastPass = arrayOfNulls<CornerPass>(n)
    /** Each corner's recent losses against its target, oldest first. */
    val losses = Array(n) { ArrayDeque<Long>() }
    val cuedOnLap = IntArray(n) { NeverCued }

    init {
        initialTargets.forEach { t -> if (t.corner in 1..n) consider(t) }
    }

    /** [p] is one of the corner's two fastest passes if it is faster than one of them. */
    private fun consider(p: CornerPass) {
        if (p.unusable()) return
        val i = p.corner - 1
        val best = fastest[i]
        best += p
        best.sortBy { it.timeMicros }
        while (best.size > ConfirmingPasses) best.removeAt(best.lastIndex)
        targets[i] = best.first()
    }

    /** The fastest time through corner [i] seen on two passes at least: the cue's target. */
    fun confirmed(i: Int): CornerPass? = fastest[i].getOrNull(ConfirmingPasses - 1)


    fun onPass(pass: CornerPass) {
        val i = pass.corner - 1
        lastPass[i] = pass
        targets[i]?.takeUnless { pass.unusable() }?.let { t ->
            losses[i].addLast(pass.timeMicros - t.timeMicros)
            while (losses[i].size > LossHistory) losses[i].removeFirst()
        }
    }

    /** A timed lap's passes become targets where they are faster. */
    fun onTimedLap(passes: List<CornerPass>) {
        passes.forEach(::consider)
    }

    val picked: Set<Int> get() = chosen.picked

    /**
     * The corners with cues at [Verbosity.Focus], at most [FocusCorners]: the session focus's primary
     * corner first (FBBD-20), then the picked corners (the ones losing the most, when more are picked),
     * then the session focus's secondary corners in order, then the corners losing the most time
     * against their targets over the last few passes.
     */
    fun focus(): Set<Int> {
        val loss = losses.map { it.takeIf { l -> l.isNotEmpty() }?.average() }
        val byLoss = chosen.picked.sortedByDescending { loss[it - 1] ?: Double.NEGATIVE_INFINITY }
        val first = (chosen.primary + byLoss + chosen.secondary).distinct().take(FocusCorners)
        val losing = loss.indices
            .filter { i -> (loss[i] ?: 0.0) >= MinLossMicros && i + 1 !in first }
            .sortedByDescending { loss[it] }
            .map { it + 1 }
        return (first + losing).take(FocusCorners).toSet()
    }

    /** The first corner whose braking zone is still ahead. */
    fun next(along: Double): Corner? = map.corners.firstOrNull { zoneStart(it) > along }

    /** Whether [along] is on a straight: clear of the last corner, before the next braking zone. */
    fun onStraight(along: Double): Boolean {
        val next = next(along)
        val previous = if (next == null) map.corners.lastOrNull() else map.corners.getOrNull(next.number - 2)
        return previous == null || along >= zoneEnd(previous) + ClearOfCornerMeters
    }

    /**
     * Where the car came to [c] from a stop on the lap that set the corners, until a lap of this
     * session has passed it: where it brakes for it at speed is not known yet (FBBD-202).
     */
    private fun unknownFrom(c: Corner): Double? = stoppedBefore.getOrNull(c.number - 1)
        ?.takeIf { lastPass[c.number - 1] == null }

    /**
     * Where braking for [c] may start: the earliest of the reference, the target and the last pass.
     * For a corner not yet known at speed, anywhere after the stop before it.
     */
    fun zoneStart(c: Corner): Double {
        val i = c.number - 1
        return unknownFrom(c)
            ?: minOf(c.brakeAt, targets[i]?.brakeAt ?: c.brakeAt, lastPass[i]?.brakeAt ?: c.brakeAt)
    }

    /**
     * Where braking for [c] ends: the latest apex of the reference, the target and the last pass.
     * For a corner not yet known at speed, the end of its measuring window.
     */
    fun zoneEnd(c: Corner): Double {
        val i = c.number - 1
        return if (unknownFrom(c) != null) {
            map.windows[i].endInclusive
        } else {
            maxOf(c.apexAt, targets[i]?.apexAt ?: c.apexAt, lastPass[i]?.apexAt ?: c.apexAt)
        }
    }
}

/**
 * Praise waiting for the straight after its corner (FBBD-243): at most one is pending, a newer one
 * replaces it, and at most one is said per lap. A praise not said within [PraiseExpiryMicros] is dropped.
 */
private class PraiseQueue {
    private var waiting: Cue? = null
    private var praisedLap = NeverCued

    /** Whether a praise was said for a pass on [lap]. */
    fun praised(lap: Int) = lap == praisedLap

    /** [cue] has no seq yet; its time is when it was found. */
    fun offer(cue: Cue) {
        waiting = cue
    }

    fun clear() {
        waiting = null
    }

    /**
     * The praise to say at [now], or null: one waits, the voice is [free], and it is heard in full
     * ([need]) before the braking zone [timeToZone] away. One that can no longer fit is dropped; while
     * the car is too slow to tell ([timeToZone] null) it waits.
     */
    fun next(now: Long, timeToZone: Long?, free: Boolean, need: (Cue) -> Long): Cue? {
        waiting?.takeIf { now > it.tMicros + PraiseExpiryMicros }?.let { waiting = null }
        val cue = waiting
        val fits = cue != null && timeToZone != null && timeToZone >= need(cue)
        if (cue != null && timeToZone != null && !fits) waiting = null
        if (!fits || !free) return null
        waiting = null
        praisedLap = cue.lap
        return cue
    }

    /** [cue] was never played: its lap may still be praised. */
    fun unsaid(cue: Cue) {
        if (praisedLap == cue.lap) praisedLap = NeverCued
    }
}

/**
 * The praise [pass] earns (FBBD-243), or null: “that worked” when the cue [result] is for moved the way
 * it asked and gained time, else “best yet” when it beat the corner's fastest pass so far by
 * [MinLossMicros]. Targets change only at the end of a timed lap, so the fastest pass here is still the
 * one from before this lap. A pass too weak to coach from is never praised.
 */
private fun praiseFor(b: CornerBook, pass: CornerPass, result: CueResult?, now: Long): Cue? {
    val corner = b.map.corners.getOrNull(pass.corner - 1)
    val fastest = b.targets.getOrNull(pass.corner - 1)
    val worked = result != null && result.improved && result.gainMicros > 0
    val best = fastest != null && fastest !== pass && fastest.timeMicros - pass.timeMicros >= MinLossMicros
    val kind = when {
        worked -> Praise.Worked
        best -> Praise.BestYet
        else -> null
    }
    return if (corner == null || kind == null || pass.unusable()) {
        null
    } else {
        Cue(
            0, now, pass.lap, CueKind.Praise, Phrases.praise(corner, kind), corner.number,
            before = if (worked) result?.cue?.before else fastest, turn = corner.label,
            cornerId = corner.cornerId, praise = kind, praised = pass,
        )
    }
}

/**
 * The corners of a [CornerMap] the coach works on first, by number: those of the session focus
 * (FBBD-20), [primary] and then [secondary] in order, and those the driver [picked] (FBBD-148).
 */
private class Chosen(val primary: List<Int>, val picked: Set<Int>, val secondary: List<Int>)

/**
 * The first timed lap of a session without an earlier reference sets the corners, [identify]d on the
 * track's. Where that lap stopped (waiting past the line, a pit stop), the corner after the stop was
 * driven from a standstill: its braking zone on a lap at speed is not known yet (FBBD-202).
 */
private fun cornersFrom(
    traced: TracedLap,
    picked: (CornerMap) -> Chosen,
    identify: (CornerMap) -> CornerMap,
): CornerBook? {
    val trace = traced.trace
    val found = findCorners(trace)
    if (found.isEmpty()) return null
    val m = identify(CornerMap(trace, found))
    val stops = trace.along.indices.filter { trace.speedsKmh[it] < StopKmh }.map { trace.along[it] }
    val stoppedBefore = m.corners.mapIndexed { i, c ->
        val after = m.corners.getOrNull(i - 1)?.apexAt ?: 0.0
        stops.lastOrNull { it >= after && it <= c.brakeAt }
    }
    return CornerBook(m, bestPasses(m.passes(trace, traced.lap.number)), picked(m), stoppedBefore)
}

/**
 * When a phrase can be spoken (FBBD-97): the car's recent speeds, whether it is braking, and whether
 * a phrase started now is heard in full before a braking zone.
 */
private class SpeechTiming(private val settings: CoachSettings, private val latencyMicros: Long) {
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    var kmh = 0.0
        private set
    var speakingUntil = Long.MIN_VALUE

    /** How old the latest fix was when it arrived ([CoachFix.ageMicros]): the car is that much further on. */
    var fixAgeMicros = 0L

    fun onSpeed(t: Long, kmh: Double) {
        this.kmh = kmh
        speeds.addLast(t to kmh)
        while (speeds.size > 1 && speeds.first().first < t - DecelWindowMicros) speeds.removeFirst()
    }

    /** From asking for [text] until it has been heard. */
    fun needMicros(text: String) =
        latencyMicros + settings.audioDelayMillis * MicrosPerMilli + Phrases.durationMicros(text)

    /** Free to speak: not braking and nothing still being said. */
    fun free(now: Long): Boolean {
        if (now < speakingUntil || speeds.size < 2) return now >= speakingUntil
        val (t0, v0) = speeds.first()
        val (t1, v1) = speeds.last()
        val dt = (t1 - t0) / MicrosPerSecond
        return !(dt > 0 && (v1 - v0) / KmhPerMps / dt < -BrakingMps2)
    }

    /**
     * Time until the car reaches [zoneStart], or null when it is too slow to tell: the shorter of the
     * time at its current speed and the reference lap's time between the two points. Out of a corner
     * the car is still accelerating, so its current speed alone would say it arrives later than it does.
     * [along] is where the fix was taken, [fixAgeMicros] ago, so that much less time is left.
     */
    fun timeTo(path: LapTrace, along: Double, zoneStart: Double): Long? {
        if (kmh < MinCueSpeedKmh) return null
        val atSpeed = (zoneStart - along) / (kmh / KmhPerMps) * MicrosPerSecond
        val byReference = path.timeAt(zoneStart) - path.timeAt(along)
        return min(atSpeed, byReference).roundToLong() - fixAgeMicros
    }

    fun said(cue: Cue): Cue {
        speakingUntil = cue.tMicros + needMicros(cue.text)
        return cue
    }

    /** [cue] was never played, so nothing is being said. */
    fun unsaid(cue: Cue) {
        if (speakingUntil == cue.tMicros + needMicros(cue.text)) speakingUntil = Long.MIN_VALUE
    }

    private companion object {
        const val MicrosPerMilli = 1_000L
    }
}

/**
 * The live coach (FBBD-27, FBBD-97). Feed it every fix of a recording at a saved track with a
 * start/finish line, in time order; it returns what to say and when.
 *
 * - **Reference.** Corners come from the [earlier] reference when it fits the track's lines, else
 *   from this session's first timed lap. Each corner's target is the second fastest pass so far, from
 *   earlier sessions and this one, so a cue only asks for what the driver has already done there on
 *   two laps at least (FBBD-177). A corner that first lap reached from a stop has no known braking
 *   zone until a lap has passed it, so nothing is said on the straight before it until then (FBBD-202).
 * - **Confidence** (FBBD-177). Each corner cue is graded from its evidence ([cueDoubts]): Low (a
 *   gap in the positions, a near stop) says nothing, Medium says it without its number ("brake a
 *   little later"), High with it.
 * - **Learning.** No corner cue until [CoachSettings.learningLaps] timed laps of this session are done;
 *   when the [earlier] reference has a session driven [sameDay] (back out after a break in the pits,
 *   FBBD-190), at most [WarmUpLaps], since the corners and targets are known already.
 * - **One cue per straight.** On the straight before a corner the coach looks at the last lap's pass
 *   there and may give one cue: brake later, brake earlier, or carry more speed. It is timed to end,
 *   after [CoachSettings.audioDelayMillis], before the braking zone; if the straight is too short it
 *   is skipped. A corner cued on one lap gets the next lap to try it before it is cued again.
 * - **Lap times** are spoken on the straight after the line, only when that straight has no corner cue.
 * - **Praise** (FBBD-243, UI-92 B, UI-93 C, UI-94 A, UI-95 A): with corner cues on and the learning laps
 *   done, a corner done better is praised on the straight after it, at most once per lap: “Turn 3, that
 *   worked” when the cue there moved the way it asked and gained time, else “Turn 3, best yet” when the
 *   pass beat the fastest one until then by [MinLossMicros]. A pass too weak to coach from is never
 *   praised. It is said only when it and that straight's corner cue both fit; when not, the cue wins.
 * - **In-lap** ([InLap], FBBD-202): nothing is said once the driver lifts off on a straight to cool
 *   down, to the end of that lap, nor after a lap ends in the pit lane, whose time is not said.
 * - **Corner numbers** are the track's (FBBD-172): each braking corner is said by the number of the
 *   track corner it is in ([identifyCorners]), as the lap analysis numbers it.
 * - **Picked corners** ([picks], FBBD-148) are matched to the coach's corners by CornerId, or by
 *   position on a track without corner identities ([pickedCorners]).
 * - **Session focus** ([focus], FBBD-20): the corners the last analysed session at the track
 *   recommends working on, the primary first, matched like the picks. At [Verbosity.Focus] the
 *   primary takes the first focus place, the picks the next, then the secondary ones; cue timing is
 *   unchanged: one cue per straight, for the corner that follows it.
 *
 * Pure and single-threaded.
 */
@Suppress("LongParameterList") // What the coach knows at the start of a session, one parameter each.
class CueEngine(
    track: Track,
    private val settings: CoachSettings,
    earlier: CoachReference? = null,
    latencyMicros: Long = DefaultLatencyMicros,
    private val picks: List<CornerPick> = emptyList(),
    sameDay: Boolean = false,
    private val focus: List<CornerPick> = emptyList(),
) {
    private val laps = LapCollector(track)
    private val speech = SpeechTiming(settings, latencyMicros)
    private val inLap = InLap()
    private val identity = track.identity
    private val picked: (CornerMap) -> Chosen = { m ->
        fun numbers(p: CornerPick) = pickedCorners(m, laps.frame, listOf(p), identity).sorted()
        Chosen(
            primary = focus.take(1).flatMap(::numbers),
            picked = pickedCorners(m, laps.frame, picks, identity),
            secondary = focus.drop(1).flatMap(::numbers),
        )
    }
    private var book: CornerBook? = earlier?.takeIf { it.fits(track) }?.let { r ->
        identified(r.map(), laps.frame, identity).let { m -> CornerBook(m, r.targets, picked(m)) }
    }
    private val learningLaps =
        if (sameDay && book != null) minOf(settings.learningLaps, WarmUpLaps) else settings.learningLaps

    private var cursor: PathCursor? = null
    private var meters: List<PassMeter> = emptyList()
    private val lapPasses = mutableListOf<CornerPass>()

    private var timedLaps = 0
    private var bestLapMicros: Long? = null
    private var seq = 0

    /** The plan for the straight being driven: the next corner, and what to say there if anything. */
    private class Plan(val corner: Int, val lap: Int, val cue: Cue?)

    private var plan: Plan? = null
    private val openCues = mutableMapOf<Int, Cue>()

    private class PendingLap(val text: String, val lap: Int, val expiresMicros: Long)

    private var pendingLap: PendingLap? = null

    private val praises = PraiseQueue()

    /** The corners being coached, once known. */
    val corners: List<Corner> get() = book?.map?.corners.orEmpty()

    /** The fastest pass so far of each corner. */
    val targetPasses: List<CornerPass> get() = book?.targets?.filterNotNull().orEmpty()

    /** The corners the driver picked, by number, once the corners are known. */
    val pickedCorners: Set<Int> get() = book?.picked.orEmpty()

    /** The corners of the session focus (FBBD-20), by number, the primary's first, once the corners are known. */
    val focusCorners: List<Int> get() = book?.chosen?.let { (it.primary + it.secondary).distinct() }.orEmpty()

    val phase: CoachPhase
        get() = when {
            settings.verbosity < Verbosity.Focus -> CoachPhase.Quiet
            book == null || timedLaps < learningLaps -> {
                // With no earlier reference the corners come from this session's first timed lap: at least one.
                val needed = if (book == null) maxOf(learningLaps, 1) else learningLaps
                CoachPhase.Learning(minOf(timedLaps, needed), needed)
            }
            else -> CoachPhase.Coaching
        }

    fun update(fix: CoachFix): CoachUpdate {
        val events = laps.update(fix)
        speech.onSpeed(fix.tMicros, laps.speedKmh)
        speech.fixAgeMicros = fix.ageMicros
        val results = mutableListOf<CueResult>()
        for (e in events) if (e is LapEvent.LapDone) onLapDone(e.lap, fix.tMicros, results)
        val along = laps.position?.let { p -> cursor?.along(p) }
        if (along == null) meters.forEach(PassMeter::lost)
        if (along != null) {
            for (m in meters) {
                val pass = m.add(along, fix.tMicros, laps.speedKmh, laps.speedMeasured)
                pass?.let { onPass(it, fix.tMicros)?.let(results::add) }
            }
        }
        inLap.update(fix.tMicros, laps.speedKmh, along?.takeIf { book?.onStraight(it) == true }, book?.map?.path)
        val cue = when {
            settings.verbosity == Verbosity.Off || inLap.quiet -> null
            along == null -> speakLapTime(fix.tMicros, null, anywhere = true)
            else -> speak(fix.tMicros, along)
        }
        return if (cue == null && results.isEmpty()) CoachUpdate.None else CoachUpdate(listOfNotNull(cue), results)
    }

    private fun onLapDone(lap: Lap, now: Long, results: MutableList<CueResult>) {
        // A corner whose window ends at the line is measured now, before its lap's passes are used.
        for (m in meters) {
            m.finish(lap.endMicros, laps.speedKmh)?.let { pass -> onPass(pass, now)?.let(results::add) }
        }
        val timed = lap.kind == LapKind.Timed
        val traced = laps.finished.lastOrNull { it.lap.number == lap.number }
        val b = book
        if (b == null && timed && traced != null) {
            book = cornersFrom(traced, picked) { identified(it, laps.frame, identity) }
        } else if (b != null && timed) {
            b.onTimedLap(lapPasses)
        }
        lapPasses.clear()
        val inPits = inLap.onLapDone(laps.speedKmh, book?.map?.path)
        if (inPits) {
            pendingLap = null
            praises.clear()
        }
        if (timed) onTimedLap(lap, now, say = !inPits)
        cursor = book?.map?.cursor()
        meters = book?.map?.meters(laps.timer.lapNumber).orEmpty()
        meters.forEach { it.start(lap.endMicros, laps.speedKmh) }
        plan = null
        openCues.clear()
    }

    /** A timed lap is done; its time is said if [say] (not after an in-lap). */
    private fun onTimedLap(lap: Lap, now: Long, say: Boolean) {
        timedLaps++
        val best = bestLapMicros
        val isBest = best == null || lap.timeMicros < best
        if (isBest) bestLapMicros = lap.timeMicros
        if (say && settings.verbosity >= Verbosity.LapTimes) {
            pendingLap = PendingLap(Phrases.lapTime(lap.timeMillis, isBest), lap.number, now + LapCueExpiryMicros)
        }
    }

    private fun onPass(pass: CornerPass, now: Long): CueResult? {
        book?.onPass(pass)
        lapPasses += pass
        val cue = openCues.remove(pass.corner)
        val before = cue?.before
        // A pass too weak to coach from (FBBD-177) says nothing about whether the cue worked: no
        // result, so the review shows no gain and no Better or Not yet for it.
        val result = if (cue == null || before == null || pass.unusable()) {
            null
        } else {
            val gain = before.timeMicros - pass.timeMicros
            CueResult(now, cue, pass, improved(cue.advice, before, pass), gain, confidenceOf(passDoubts(pass)))
        }
        val learned = settings.verbosity >= Verbosity.Focus && timedLaps >= learningLaps
        val b = book
        if (learned && b != null && !praises.praised(pass.lap)) praiseFor(b, pass, result, now)?.let(praises::offer)
        return result
    }

    /**
     * [cue] was not heard (it came too late to say, or the voice refused it): nothing is measured
     * against it, and its corner may be cued again on the next lap.
     */
    fun unsaid(cue: Cue) {
        speech.unsaid(cue)
        if (cue.kind == CueKind.Praise) {
            praises.unsaid(cue)
            return
        }
        val corner = cue.corner ?: return
        if (openCues[corner]?.seq == cue.seq) openCues.remove(corner)
        book?.cuedOnLap?.let { if (it[corner - 1] == cue.lap) it[corner - 1] = NeverCued }
    }

    /** At most one cue for this fix, with the car [along] the reference path. */
    @Suppress("ReturnCount") // Each early return is a reason to stay quiet on this fix.
    private fun speak(now: Long, along: Double): Cue? {
        val b = book ?: return null
        val next = b.next(along) ?: return null
        val lap = laps.timer.lapNumber
        // Still in the previous corner: not on the straight yet.
        val previous = b.map.corners.getOrNull(next.number - 2)
        if (previous != null && along < b.zoneEnd(previous) + ClearOfCornerMeters) return null
        val p = plan?.takeIf { it.corner == next.number && it.lap == lap }
            ?: Plan(next.number, lap, cornerCue(b, next, now)).also { plan = it }
        val zoneStart = b.zoneStart(next)
        val time = speech.timeTo(b.map.path, along, zoneStart)
        val cue = p.cue
        // Praise first, when it and this straight's corner cue both fit (UI-94 A: else the cue wins).
        val cueNeed = cue?.let { speech.needMicros(it.text) + MarginMicros } ?: 0L
        val praise = praises.next(now, time, speech.free(now)) { speech.needMicros(it.text) + MarginMicros + cueNeed }
        if (praise != null) return speech.said(praise.copy(seq = ++seq, tMicros = now, zoneMeters = zoneStart - along))
        return if (cue != null) speakCorner(cue, now, time, zoneStart - along) else speakLapTime(now, time)
    }


    /**
     * Says [planned] when it fits on this straight; drops it once it no longer can. [zoneMeters] is the
     * distance to the braking zone, logged with the cue.
     */
    private fun speakCorner(planned: Cue, now: Long, timeToZone: Long?, zoneMeters: Double): Cue? {
        val need = speech.needMicros(planned.text) + MarginMicros
        val late = timeToZone != null && timeToZone < need
        if (late) plan = Plan(planned.corner!!, planned.lap, null)
        val ready = timeToZone != null && !late && timeToZone <= need + LeadMicros
        if (!ready || !speech.free(now)) return null
        plan = Plan(planned.corner!!, planned.lap, null)
        pendingLap = null // One cue per straight.
        val cue = speech.said(planned.copy(seq = ++seq, tMicros = now, zoneMeters = zoneMeters))
        openCues[planned.corner] = cue
        book?.cuedOnLap?.set(planned.corner - 1, planned.lap)
        return cue
    }

    /**
     * Says the pending lap time if it is heard in full before the next braking zone, [timeToZone] away.
     * Off the corner map ([anywhere]) nothing is known about braking zones, so it goes as soon as the
     * car is not braking.
     */
    private fun speakLapTime(now: Long, timeToZone: Long?, anywhere: Boolean = false): Cue? {
        val pending = pendingLap?.takeIf { now <= it.expiresMicros }
        if (pending == null) pendingLap = null
        val need = pending?.let { speech.needMicros(it.text) + MarginMicros }
        val fits = anywhere || (timeToZone != null && need != null && timeToZone >= need)
        if (pending == null || !fits || !speech.free(now)) return null
        pendingLap = null
        return speech.said(Cue(++seq, now, pending.lap, CueKind.LapTime, pending.text))
    }

    /** The corner cue for [corner] on this straight, or null if there is nothing to say. */
    private fun cornerCue(b: CornerBook, corner: Corner, now: Long): Cue? {
        val i = corner.number - 1
        val lap = laps.timer.lapNumber
        val last = b.lastPass[i]?.takeIf { it.lap == lap - 1 }
        val target = b.confirmed(i)?.takeIf { it !== last }
        if (last == null || target == null || !mayCue(b, corner, lap)) return null
        val unidentified = identity != null && corner.cornerId == null
        val doubts = cueDoubts(last, target, b.losses[i].toList(), unidentified)
        val confidence = confidenceOf(doubts)
        // Low: weak evidence gives no advice at all; Medium: advice without its number.
        val advice = advise(last, target)?.takeIf { confidence.advises }?.withPrecision(confidence.precise)
        return advice?.let {
            Cue(
                0, now, lap, CueKind.Corner, Phrases.corner(corner, it), corner.number, it, last, target,
                turn = corner.label, cornerId = corner.cornerId, confidence = confidence, doubts = doubts,
            )
        }
    }

    private fun mayCue(b: CornerBook, corner: Corner, lap: Int): Boolean {
        val learned = settings.verbosity >= Verbosity.Focus && timedLaps >= learningLaps
        // The driver gets a lap to try the last cue.
        val rested = b.cuedOnLap[corner.number - 1] != lap - 1
        return learned && rested && (settings.verbosity == Verbosity.Full || corner.number in b.focus())
    }
}

/** What to ask for at a corner, given the last pass and the fastest one (FBBD-97); null if nothing. */
fun advise(pass: CornerPass, target: CornerPass): Advice? {
    if (pass.timeMicros - target.timeMicros < MinLossMicros) return null
    val brake = pass.brakeAt - target.brakeAt // Negative: braked earlier than the target.
    val speed = pass.minKmh - target.minKmh
    return when {
        brake <= -BrakeToleranceMeters -> {
            val metres = (min(-brake, MaxBrakeLaterMeters) / MetresStep).roundToInt() * MetresStep
            Advice.BrakeLater(maxOf(MetresStep, metres))
        }
        speed <= -SpeedToleranceKmh && brake >= BrakeToleranceMeters -> Advice.BrakeEarlier
        speed <= -SpeedToleranceKmh -> Advice.CarrySpeed(min(-speed, MaxCarryKmh).roundToInt())
        else -> null
    }
}

/** Whether [after] moved the way [advice] asked, from [before]: at least half of what was asked. */
fun improved(advice: Advice?, before: CornerPass, after: CornerPass): Boolean = when (advice) {
    is Advice.BrakeLater -> after.brakeAt - before.brakeAt >= advice.meters / 2.0
    Advice.BrakeEarlier -> before.brakeAt - after.brakeAt >= BrakeToleranceMeters / 2
    is Advice.CarrySpeed -> after.minKmh - before.minKmh >= advice.kmh / 2.0
    null -> after.timeMicros < before.timeMicros
}
