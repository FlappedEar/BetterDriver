package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.gear.DriveSample
import com.flappedear.betterdriver.timing.gear.GearTracker
import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.gear.fitGearbox
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.SessionLaps
import com.flappedear.betterdriver.timing.replay.trackFrame

/**
 * The lap being driven at one moment, or the run on a point-to-point track (FBBD-155). [number] and
 * [lapMicros] (time since the lap started) are always known; [kind] is null for the lap still being
 * driven when the session stopped.
 * [deltaMicros] is the live delta to the best lap set before this one (FBBD-449), matched by track
 * position as on the track screen: the session's fastest lap shows what it gains on the previous
 * best, not 0.0 against itself. Null on out and in laps, and when no earlier lap counts for best.
 * [isBest]: this lap is the session's best lap.
 * [phase] is [LapPhase.Running] except in a single-lap export ([OverlayData]'s hotlap).
 */
data class OverlayLap(
    val number: Int,
    val kind: LapKind?,
    val lapMicros: Long,
    val deltaMicros: Long?,
    val isBest: Boolean,
    val phase: LapPhase = LapPhase.Running,
)

/**
 * Where a single-lap export is in its lap, as FlappedEar Overlays' hotlap lap time shows it: [Ready]
 * in the seconds before the start line (0:00), [Running] during the lap, [Finished] after the line
 * (the lap's final time and delta, held).
 */
enum class LapPhase { Ready, Running, Finished }

/**
 * The lap's pace at a point of the map's trail (FBBD-451): [Ahead] of the best lap before it (live
 * delta -0.001 s or less), drawn green; [Behind] (slower, or level), drawn orange.
 */
enum class Pace {
    Ahead,
    Behind,
    ;

    companion object {
        /** The pace for live delta [deltaMicros]; null without one (the trail keeps its single colour). */
        fun of(deltaMicros: Long?): Pace? = when {
            deltaMicros == null -> null
            deltaMicros <= AheadMicros -> Ahead
            else -> Behind
        }

        /** -0.001 s, the delta's last shown digit: Arek's "delta <= -0.001 green, above orange". */
        const val AheadMicros = -1_000L
    }
}

/** A point of the map's trail and the lap's [pace] there; null pace: no reference lap. */
data class TrailPoint(val xy: Xy, val pace: Pace?)

/** A stretch of the map's trail without a gap and in one [pace]; drawn as one line. */
class PaceRun(val points: List<Xy>, val pace: Pace?)

/**
 * [points] as lines to draw: broken at each null (no fix), and split where the pace changes. A segment
 * takes the pace of its later point, and the run after a change starts at the point before it, so
 * the line stays joined.
 */
fun paceRuns(points: List<TrailPoint?>): List<PaceRun> {
    val out = ArrayList<PaceRun>()
    var run = ArrayList<Xy>()
    var pace: Pace? = null
    fun close() {
        if (run.size > 1) out += PaceRun(run, pace)
    }
    for (p in points) {
        when {
            p == null -> {
                close()
                run = ArrayList()
            }
            // The first segment takes its later point's pace.
            run.size < 2 -> {
                run += p.xy
                pace = p.pace
            }
            p.pace != pace -> {
                close()
                run = arrayListOf(run.last(), p.xy)
                pace = p.pace
            }
            else -> run += p.xy
        }
    }
    close()
    return out
}

/** What the data overlay shows at one moment of the session; a null field is shown as a dash or left out. */
data class OverlayFrame(
    val speedKmh: Double?,
    val lap: OverlayLap?,
    /** In the frame of [OverlayData.outline]. */
    val position: Xy?,
    val g: GForce?,
    /** The car's own values (FBBD-347): engine, pedals, wheel, temperatures, the driver's heart rate. */
    val car: CarFrame = CarFrame(),
    /**
     * The best lap time so far (FBBD-449): the fastest lap counting for best that had finished by this
     * moment, as the track screen showed it then; null before the first.
     */
    val bestLapMicros: Long? = null,
)

/**
 * The car's G from its accelerometers, already turned to the car, levelled and, with a receiver and
 * the phone both recording, averaged (FBBD-226): (session time in µs, g) pairs, [forward] positive
 * speeding up, [right] positive to the right; null for a direction no accelerometer agrees on.
 */
class MeasuredG(val forward: List<Pair<Long, Double>>?, val right: List<Pair<Long, Double>>?)

/**
 * Everything the video export's data overlay draws (FBBD-152), looked up by session time: speed,
 * position, G, and the lap with its delta to the best lap set before it (FBBD-449).
 *
 * Built once per export from the session's sample sets and its replayed laps ([laps], null when
 * the session was not at a saved track with a start/finish line: then there is no lap or delta).
 * G comes from [measured], the accelerometers as the analysis corrects them, so the video shows the
 * G-G diagram's numbers; a direction without one comes from the GNSS path.
 *
 * With [hotlap], a lap's number, every moment shows that lap only (a single-lap export with a few
 * seconds before and after it, as Overlays exports one): before its start it is [LapPhase.Ready],
 * after its end [LapPhase.Finished].
 *
 * The car's values (FBBD-347) come from the session's [channels] by name ([CarValue]).
 *
 * Not thread-safe: the exporter draws frames one at a time.
 */
class OverlayData(
    sets: List<SampleSet>,
    laps: SessionLaps?,
    measured: MeasuredG? = null,
    hotlap: Int? = null,
    channels: Collection<ChannelDef> = emptyList(),
) {
    private val x: TimeSeries
    private val y: TimeSeries
    private val speed: TimeSeries
    private val gLong: TimeSeries
    private val gLat: TimeSeries
    private val traces: List<LapTrace> = laps?.laps.orEmpty().sortedBy { it.lap.startMicros }
    private val best: LapTrace? = laps?.best

    /**
     * The laps that count for best, in the order they finished, and the fastest of each one and those
     * before it (FBBD-449): the running best, as the track screen's best lap moved during the session.
     */
    private val counted: List<Lap> =
        (laps?.book?.laps ?: traces.map { it.lap }).filter { it.countsForBest }.sortedBy { it.endMicros }
    private val countedEnds = LongArray(counted.size) { counted[it].endMicros }
    private val runningBest: List<Lap> = runningMin(counted)
    private val starts = LongArray(traces.size) { traces[it].lap.startMicros }
    private val alignments = HashMap<Int, LapAlignment>()
    private val hotlap: LapTrace? = hotlap?.let { n -> traces.firstOrNull { it.lap.number == n } }
    private val car: Map<CarValue, TimeSeries>

    /**
     * The car's gearbox as this session's engine speed and road speed show it (FBBD-397), for the
     * gear on the RPM gauge (UI-310 A); null without engine speed or when no known gearbox fits.
     */
    val gearbox: Gearbox?

    /** The gear from each engine speed reading on, as [GearTracker] reads it in time order (0: none). */
    private val gearTimes: LongArray
    private val gearSteps: IntArray

    /** The car's values this session has samples of (FBBD-347): the overlay leaves out the rest. */
    val carValues: Set<CarValue> get() = car.keys

    /**
     * The session's best lap time; null without one. A composite lap's video compares with it; the
     * other videos show [OverlayFrame.bestLapMicros], the best so far.
     */
    val bestLapMicros: Long? = best?.lap?.let { it.endMicros - it.startMicros }

    /** The start/finish line's ends, in the frame of [outline]; null without a saved track's line. */
    val startLine: Pair<Xy, Xy>?

    /** The laps are runs of a point-to-point track (FBBD-155): the overlay says "Run", and between runs shows none. */
    val pointToPoint: Boolean = laps?.track?.pointToPoint == true

    /** The track's shape for the map: the best lap's path, else the longest lap's, else every position. */
    val outline: List<Xy>

    init {
        val sorted = sets.sortedBy { it.tMicros }
        // One fix per time, each with the receiver's speed when the set holds one.
        val fixes = ArrayList<Fix>(sorted.size)
        val fixSpeeds = ArrayList<Double?>(sorted.size)
        for (s in sorted) {
            val f = s.toFix() ?: continue
            if (fixes.isEmpty() || f.tMicros > fixes.last().tMicros) {
                fixes += f
                fixSpeeds += s.valueOf(GnssChannels.SPEED_KMH.id)?.div(KmhPerMs)
            }
        }
        val frame = laps?.let { trackFrame(it.track) }
            ?: fixes.firstOrNull()?.let { runCatching { LocalFrame(GeoPoint(it.lat, it.lon)) }.getOrNull() }
        val path = fixes.map { f -> frame?.toXy(f.lat, f.lon) ?: Xy(0.0, 0.0) }
        val times = LongArray(fixes.size) { fixes[it].tMicros }
        x = TimeSeries.of(times.indices.map { times[it] to path[it].x })
        y = TimeSeries.of(times.indices.map { times[it] to path[it].y })
        speed = TimeSeries.of(sorted.mapNotNull { s -> s.valueOf(GnssChannels.SPEED_KMH.id)?.let { s.tMicros to it } })

        val v = if (fixSpeeds.all { it != null }) DoubleArray(fixSpeeds.size) { fixSpeeds[it]!! } else null
        val g = gnssG(times, path, v)
        val gnssLong = TimeSeries.of(times.indices.mapNotNull { i -> g[i]?.let { times[i] to it.longitudinal } })
        val gnssLat = TimeSeries.of(times.indices.mapNotNull { i -> g[i]?.let { times[i] to it.lateral } })
        fun smoothed(samples: List<Pair<Long, Double>>?): TimeSeries? =
            samples?.takeIf { it.size >= 2 }?.let { TimeSeries.of(it).smoothed(AccelHalfWindowMicros) }
        gLong = smoothed(measured?.forward) ?: gnssLong
        gLat = smoothed(measured?.right) ?: gnssLat
        outline = (best ?: traces.maxByOrNull { it.lengthMeters })?.points?.map { it.xy } ?: path
        startLine = laps?.track?.startFinish?.let { line ->
            frame?.let { it.toXy(line.left) to it.toXy(line.right) }
        }
        val ids = carChannelIds(channels)
        car = carSeries(sorted, ids)
        val drive = ids[CarValue.Rpm]?.let { driveSamples(sorted, it, speed) }.orEmpty()
        gearbox = drive.takeIf { it.isNotEmpty() }?.let { fitGearbox(listOf(it)) }
        val tracker = gearbox?.let(::GearTracker)
        val tracked = if (tracker == null) emptyList() else drive.map { d ->
            val t = (d.tSeconds * MicrosPerSecond).toLong()
            t to (tracker.next(t, d.rpm ?: 0.0, d.speedMps) ?: 0)
        }
        gearTimes = LongArray(tracked.size) { tracked[it].first }
        gearSteps = IntArray(tracked.size) { tracked[it].second }
    }

    /** The G at [sessionMicros], as [at] has it; the radar's trail reads it back in time. */
    fun gAt(sessionMicros: Long): GForce? {
        val gl = gLong.at(sessionMicros)
        val gt = gLat.at(sessionMicros)
        return if (gl != null && gt != null) GForce(gl, gt) else null
    }

    /**
     * Where the car went up to [sessionMicros], as Overlays' map trail (KAN-271): from the start of
     * the lap being driven, else the last [TrailFallbackMicros], at most [TrailMaxPoints] points at
     * least [TrailMinStepMicros] apart. A null breaks the line where there was no fix.
     *
     * Each point has the pace there (FBBD-451): the live delta then to the best lap set before this
     * one (FBBD-449), as [Pace.of] reads it, so the map shows where the lap gained and lost. Without
     * a reference (out and in laps, the first timed lap, outside a lap) every pace is null.
     */
    fun paceTrail(sessionMicros: Long): List<TrailPoint?> {
        val trace = traces.lastOrNull { it.lap.startMicros <= sessionMicros && sessionMicros < it.lap.endMicros }
        val lapStart = trace?.lap?.startMicros
        val start = lapStart ?: (sessionMicros - TrailFallbackMicros)
        val step = maxOf(TrailMinStepMicros, (sessionMicros - start) / TrailMaxPoints)
        val paced = trace?.takeIf { it.lap.kind == LapKind.Timed && referenceFor(it.lap) != null }
        val out = ArrayList<TrailPoint?>()
        var open = false
        fun add(t: Long) {
            val px = x.at(t)
            val py = y.at(t)
            if (px != null && py != null) {
                val pace = paced?.let { Pace.of(deltaAt(it, t - it.lap.startMicros)) }
                out += TrailPoint(Xy(px, py), pace)
                open = true
            } else if (open) {
                out += null
                open = false
            }
        }
        var t = start
        while (t < sessionMicros) {
            add(t)
            t += step
        }
        add(sessionMicros)
        return out
    }

    private fun carAt(t: Long): CarFrame {
        fun v(value: CarValue) = car[value]?.at(t)
        val rpm = v(CarValue.Rpm)
        val gear = gearAt(t)
        return CarFrame(
            rpm = rpm,
            gear = gear,
            gears = gearbox != null,
            throttlePct = v(CarValue.Accelerator) ?: v(CarValue.Throttle),
            brakePct = v(CarValue.Brake),
            steeringDeg = v(CarValue.SteeringAngle),
            heartRate = v(CarValue.HeartRate),
            oilC = v(CarValue.OilTemp),
            gearboxC = v(CarValue.GearboxTemp),
            coolantC = v(CarValue.CoolantTemp),
            tyreTemps = CarValue.TyreTemps.map(::v),
            tyrePressures = CarValue.TyrePressures.map(::v),
        )
    }

    /**
     * The gear at [t]: held through the converter's slip and changed where engine speed steps to
     * another gear, as the Gear tile reads it; null standing or where engine speed is missing.
     */
    @Suppress("ReturnCount")
    private fun gearAt(t: Long): Int? {
        if (gearTimes.isEmpty() || t < gearTimes[0]) return null
        val i = floorIndex(gearTimes, t)
        if (t - gearTimes[i] > GearTracker.MaxGapMicros) return null
        return gearSteps[i].takeIf { it > 0 }
    }

    fun at(sessionMicros: Long): OverlayFrame {
        val px = x.at(sessionMicros)
        val py = y.at(sessionMicros)
        val gl = gLong.at(sessionMicros)
        val gt = gLat.at(sessionMicros)
        return OverlayFrame(
            speedKmh = speed.at(sessionMicros),
            lap = hotlap?.let { hotlapAt(it, sessionMicros) } ?: lapAt(sessionMicros),
            position = if (px != null && py != null) Xy(px, py) else null,
            g = if (gl != null && gt != null) GForce(gl, gt) else null,
            car = if (car.isEmpty()) CarFrame() else carAt(sessionMicros),
            // A single-lap export shows, before its line, the best its lap will be compared with.
            bestLapMicros = bestBy(hotlap?.lap?.startMicros?.coerceAtLeast(sessionMicros) ?: sessionMicros)?.timeMicros,
        )
    }

    /** The fastest lap counting for best that had finished by [t]; null before the first (FBBD-449). */
    private fun bestBy(t: Long): Lap? =
        if (counted.isEmpty() || t < countedEnds[0]) null else runningBest[floorIndex(countedEnds, t)]

    /**
     * The lap [lap] is compared with: the best lap finished before it started (FBBD-449), with its trace
     * for the live delta; null for the first lap counting for best, or when that lap has no trace.
     */
    private fun referenceFor(lap: Lap): LapTrace? {
        val before = bestBy(lap.startMicros) ?: return null
        return traces.firstOrNull { it.lap.number == before.number }
    }

    /**
     * The lap at [t]; after the last finished lap, the one being driven when the session stopped. A
     * point-to-point track has no such lap: after the last run's finish comes the drive back.
     */
    private fun lapAt(t: Long): OverlayLap? {
        val last = traces.lastOrNull()?.lap
        val trace = if (traces.isEmpty() || t < starts.first()) null else traces[floorIndex(starts, t)]
        return when {
            last == null || trace == null -> null
            t >= last.endMicros && pointToPoint -> null
            t >= last.endMicros -> OverlayLap(last.number + 1, null, t - last.endMicros, null, isBest = false)
            // A lap whose path could not be traced (a GNSS gap at the line), or the drive back between
            // point-to-point runs, leaves a hole: no lap is shown there.
            t >= trace.lap.endMicros -> null
            else -> {
                val lapMicros = t - trace.lap.startMicros
                val isBest = trace.lap.number == best?.lap?.number
                OverlayLap(trace.lap.number, trace.lap.kind, lapMicros, deltaAt(trace, lapMicros), isBest)
            }
        }
    }

    /** Lap [trace] only, whatever the time: 0:00 before its start, its final time and delta after its end. */
    private fun hotlapAt(trace: LapTrace, t: Long): OverlayLap {
        val lap = trace.lap
        val isBest = lap.number == best?.lap?.number
        return when {
            t < lap.startMicros -> OverlayLap(lap.number, lap.kind, 0L, null, isBest, LapPhase.Ready)
            t >= lap.endMicros -> {
                val delta = bestBy(lap.startMicros)?.takeIf { lap.kind == LapKind.Timed }
                    ?.let { lap.timeMicros - it.timeMicros }
                OverlayLap(lap.number, lap.kind, lap.timeMicros, delta, isBest, LapPhase.Finished)
            }
            else -> {
                val lapMicros = t - lap.startMicros
                OverlayLap(lap.number, lap.kind, lapMicros, deltaAt(trace, lapMicros), isBest)
            }
        }
    }

    private fun deltaAt(trace: LapTrace, lapMicros: Long): Long? {
        val reference = (if (trace.lap.kind == LapKind.Timed) referenceFor(trace.lap) else null) ?: return null
        val alignment = alignments.getOrPut(trace.lap.number) { LapAlignment(reference, trace) }
        // Time this lap took to get here minus the time the best lap before it took to the same place.
        return lapMicros - alignment.referenceMicrosAt(lapMicros)
    }

    private companion object {
        /** About a third of a second in all: smooth enough to read, quick enough for a braking point. */
        const val AccelHalfWindowMicros = 150_000L
        const val TrailFallbackMicros = 30_000_000L
        const val TrailMinStepMicros = 100_000L
        const val TrailMaxPoints = 600L
    }
}

/** [OverlayData.paceTrail] without the pace: the map's trail as a line. */
fun OverlayData.trail(sessionMicros: Long): List<Xy?> = paceTrail(sessionMicros).map { it?.xy }

/** For each of [laps], the fastest of it and those before it; on a tie the earlier lap stays. */
private fun runningMin(laps: List<Lap>): List<Lap> {
    val out = ArrayList<Lap>(laps.size)
    for (lap in laps) {
        val b = out.lastOrNull()
        out += if (b == null || lap.timeMicros < b.timeMicros) lap else b
    }
    return out
}

private fun SampleSet.valueOf(channelId: Int): Double? =
    values.firstOrNull { it.channelId == channelId }?.value?.takeIf { it.isFinite() }

private const val KmhPerMs = 3.6

/**
 * The engine speed readings of channel [rpmId] in [sorted] with the road speed then, at most one every
 * [GearStepMicros]: CAN sends engine speed 100 times a second, and ten readings a second fit a gearbox.
 */
@Suppress("LoopWithTooManyJumpStatements") // Each reading without both values or too soon is skipped.
private fun driveSamples(sorted: List<SampleSet>, rpmId: Int, speed: TimeSeries): List<DriveSample> {
    val out = ArrayList<DriveSample>()
    var last = Long.MIN_VALUE
    for (s in sorted) {
        if (last != Long.MIN_VALUE && s.tMicros - last < GearStepMicros) continue
        val rpm = s.valueOf(rpmId) ?: continue
        val kmh = speed.at(s.tMicros) ?: continue
        out += DriveSample(s.tMicros / MicrosPerSecond, kmh / KmhPerMs, rpm, null)
        last = s.tMicros
    }
    return out
}

private const val GearStepMicros = 90_000L

/** A series for each of [ids]' values that has samples in [sorted]. */
private fun carSeries(sorted: List<SampleSet>, ids: Map<CarValue, Int>): Map<CarValue, TimeSeries> {
    if (ids.isEmpty()) return emptyMap()
    val byId = ids.entries.groupBy({ it.value }, { it.key })
    val samples = HashMap<Int, ArrayList<Pair<Long, Double>>>()
    for (s in sorted) {
        for (v in s.values) {
            val values = byId[v.channelId] ?: continue
            if (v.value.isFinite() && !(v.value == 0.0 && values.all { it.zeroIsNone })) {
                samples.getOrPut(v.channelId) { ArrayList() } += s.tMicros to v.value
            }
        }
    }
    return ids.mapNotNull { (value, id) -> samples[id]?.let { value to TimeSeries.of(it, value.maxGapMicros) } }.toMap()
}
