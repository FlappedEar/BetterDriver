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

/** [CornerBook.cuedOnLap] of a corner not cued yet: far enough below any lap that it is always rested. */
private const val NeverCued = Int.MIN_VALUE / 2

/** Losses kept per corner for picking focus corners. */
private const val LossHistory = 3

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
 * pass, recent losses against the target, and the lap it was last cued on. [picked] are the corners
 * the driver picked in the lap analysis (FBBD-148).
 */
private class CornerBook(val map: CornerMap, initialTargets: List<CornerPass>, val picked: Set<Int>) {
    private val n = map.corners.size
    val targets = arrayOfNulls<CornerPass>(n)
    val lastPass = arrayOfNulls<CornerPass>(n)
    private val losses = Array(n) { ArrayDeque<Long>() }
    val cuedOnLap = IntArray(n) { NeverCued }

    init {
        initialTargets.forEach { t -> if (t.corner in 1..n) targets[t.corner - 1] = t }
    }

    fun onPass(pass: CornerPass) {
        val i = pass.corner - 1
        lastPass[i] = pass
        targets[i]?.let { t ->
            losses[i].addLast(pass.timeMicros - t.timeMicros)
            while (losses[i].size > LossHistory) losses[i].removeFirst()
        }
    }

    /** A timed lap's passes become targets where they are faster. */
    fun onTimedLap(passes: List<CornerPass>) {
        for (p in passes) {
            val i = p.corner - 1
            val t = targets[i]
            if (t == null || p.timeMicros < t.timeMicros) targets[i] = p
        }
    }

    /**
     * The corners with cues at [Verbosity.Focus], at most [FocusCorners]: the picked corners first (the
     * ones losing the most, when more are picked), then the corners losing the most time against their
     * targets over the last few passes.
     */
    fun focus(): Set<Int> {
        val loss = losses.map { it.takeIf { l -> l.isNotEmpty() }?.average() }
        val chosen = picked.sortedByDescending { loss[it - 1] ?: Double.NEGATIVE_INFINITY }.take(FocusCorners)
        val losing = loss.indices
            .filter { i -> (loss[i] ?: 0.0) >= MinLossMicros && i + 1 !in chosen }
            .sortedByDescending { loss[it] }
            .map { it + 1 }
        return (chosen + losing).take(FocusCorners).toSet()
    }

    /** The first corner whose braking zone is still ahead. */
    fun next(along: Double): Corner? = map.corners.firstOrNull { zoneStart(it) > along }

    /** Where braking for [c] may start: the earliest of the reference, the target and the last pass. */
    fun zoneStart(c: Corner): Double {
        val i = c.number - 1
        return minOf(c.brakeAt, targets[i]?.brakeAt ?: c.brakeAt, lastPass[i]?.brakeAt ?: c.brakeAt)
    }

    /** Where braking for [c] ends: the latest apex of the reference, the target and the last pass. */
    fun zoneEnd(c: Corner): Double {
        val i = c.number - 1
        return maxOf(c.apexAt, targets[i]?.apexAt ?: c.apexAt, lastPass[i]?.apexAt ?: c.apexAt)
    }
}

/** The first timed lap of a session without an earlier reference sets the corners, [identify]d on the track's. */
private fun cornersFrom(
    traced: TracedLap,
    picked: (CornerMap) -> Set<Int>,
    identify: (CornerMap) -> CornerMap,
): CornerBook? {
    val found = findCorners(traced.trace)
    if (found.isEmpty()) return null
    val m = identify(CornerMap(traced.trace, found))
    return CornerBook(m, bestPasses(m.passes(traced.trace, traced.lap.number)), picked(m))
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
     */
    fun timeTo(path: LapTrace, along: Double, zoneStart: Double): Long? {
        if (kmh < MinCueSpeedKmh) return null
        val atSpeed = (zoneStart - along) / (kmh / KmhPerMps) * MicrosPerSecond
        val byReference = path.timeAt(zoneStart) - path.timeAt(along)
        return min(atSpeed, byReference).roundToLong()
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
 *   from this session's first timed lap. Each corner's target is the fastest pass so far, from earlier
 *   sessions and this one, so a cue only asks for what the driver has already done there.
 * - **Learning.** No corner cue until [CoachSettings.learningLaps] timed laps of this session are done.
 * - **One cue per straight.** On the straight before a corner the coach looks at the last lap's pass
 *   there and may give one cue: brake later, brake earlier, or carry more speed. It is timed to end,
 *   after [CoachSettings.audioDelayMillis], before the braking zone; if the straight is too short it
 *   is skipped. A corner cued on one lap gets the next lap to try it before it is cued again.
 * - **Lap times** are spoken on the straight after the line, only when that straight has no corner cue.
 * - **Corner numbers** are the track's (FBBD-172): each braking corner is said by the number of the
 *   track corner it is in ([identifyCorners]), as the lap analysis numbers it.
 * - **Picked corners** ([picks], FBBD-148) are matched to the coach's corners by CornerId, or by
 *   position on a track without corner identities ([pickedCorners]). At [Verbosity.Focus] they take
 *   the focus places first; cue timing is unchanged: one cue per straight, for the corner that
 *   follows it.
 *
 * Pure and single-threaded.
 */
class CueEngine(
    track: Track,
    private val settings: CoachSettings,
    earlier: CoachReference? = null,
    latencyMicros: Long = DefaultLatencyMicros,
    private val picks: List<CornerPick> = emptyList(),
) {
    private val laps = LapCollector(track)
    private val speech = SpeechTiming(settings, latencyMicros)
    private val identity = track.identity
    private val picked: (CornerMap) -> Set<Int> = { pickedCorners(it, laps.frame, picks, identity) }
    private var book: CornerBook? = earlier?.takeIf { it.fits(track) }?.let { r ->
        identified(r.map(), laps.frame, identity).let { m -> CornerBook(m, r.targets, picked(m)) }
    }

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

    /** The corners being coached, once known. */
    val corners: List<Corner> get() = book?.map?.corners.orEmpty()

    /** The fastest pass so far of each corner. */
    val targetPasses: List<CornerPass> get() = book?.targets?.filterNotNull().orEmpty()

    /** The corners the driver picked, by number, once the corners are known. */
    val pickedCorners: Set<Int> get() = book?.picked.orEmpty()

    val phase: CoachPhase
        get() = when {
            settings.verbosity < Verbosity.Focus -> CoachPhase.Quiet
            book == null || timedLaps < settings.learningLaps -> {
                // With no earlier reference the corners come from this session's first timed lap: at least one.
                val needed = if (book == null) maxOf(settings.learningLaps, 1) else settings.learningLaps
                CoachPhase.Learning(minOf(timedLaps, needed), needed)
            }
            else -> CoachPhase.Coaching
        }

    fun update(fix: CoachFix): CoachUpdate {
        val events = laps.update(fix)
        speech.onSpeed(fix.tMicros, laps.speedKmh)
        val results = mutableListOf<CueResult>()
        for (e in events) if (e is LapEvent.LapDone) onLapDone(e.lap, fix.tMicros, results)
        val along = laps.position?.let { p -> cursor?.along(p) }
        if (along == null) meters.forEach(PassMeter::lost)
        if (along != null) {
            for (m in meters) {
                m.add(along, fix.tMicros, laps.speedKmh)?.let { pass -> onPass(pass, fix.tMicros)?.let(results::add) }
            }
        }
        val cue = when {
            settings.verbosity == Verbosity.Off -> null
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
        if (timed) onTimedLap(lap, now)
        cursor = book?.map?.cursor()
        meters = book?.map?.meters(laps.timer.lapNumber).orEmpty()
        meters.forEach { it.start(lap.endMicros, laps.speedKmh) }
        plan = null
        openCues.clear()
    }

    private fun onTimedLap(lap: Lap, now: Long) {
        timedLaps++
        val best = bestLapMicros
        val isBest = best == null || lap.timeMicros < best
        if (isBest) bestLapMicros = lap.timeMicros
        if (settings.verbosity >= Verbosity.LapTimes) {
            pendingLap = PendingLap(Phrases.lapTime(lap.timeMillis, isBest), lap.number, now + LapCueExpiryMicros)
        }
    }

    private fun onPass(pass: CornerPass, now: Long): CueResult? {
        book?.onPass(pass)
        lapPasses += pass
        val cue = openCues.remove(pass.corner)
        val before = cue?.before
        return if (cue == null || before == null) {
            null
        } else {
            CueResult(now, cue, pass, improved(cue.advice, before, pass), before.timeMicros - pass.timeMicros)
        }
    }

    /**
     * [cue] was not heard (it came too late to say, or the voice refused it): nothing is measured
     * against it, and its corner may be cued again on the next lap.
     */
    fun unsaid(cue: Cue) {
        speech.unsaid(cue)
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
        val target = b.targets[i]?.takeIf { it !== last }
        val advice = if (last != null && target != null && mayCue(b, corner, lap)) advise(last, target) else null
        return advice?.let {
            Cue(
                0, now, lap, CueKind.Corner, Phrases.corner(corner, it), corner.number, it, last, target,
                turn = corner.label, cornerId = corner.cornerId,
            )
        }
    }

    private fun mayCue(b: CornerBook, corner: Corner, lap: Int): Boolean {
        val learned = settings.verbosity >= Verbosity.Focus && timedLaps >= settings.learningLaps
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
