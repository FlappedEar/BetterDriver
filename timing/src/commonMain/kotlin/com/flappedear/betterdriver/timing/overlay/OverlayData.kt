package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.gear.DriveSample
import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.gear.fitGearbox
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
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
 * [deltaMicros] is the live delta to the session's best lap, matched by track position as on the
 * track screen; null on out and in laps, and without a best lap.
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

/** What the data overlay shows at one moment of the session; a null field is shown as a dash or left out. */
data class OverlayFrame(
    val speedKmh: Double?,
    val lap: OverlayLap?,
    /** In the frame of [OverlayData.outline]. */
    val position: Xy?,
    val g: GForce?,
    /** The car's own values (FBBD-347): engine, pedals, wheel, temperatures, the driver's heart rate. */
    val car: CarFrame = CarFrame(),
)

/**
 * The car's G from its accelerometers, already turned to the car, levelled and, with a receiver and
 * the phone both recording, averaged (FBBD-226): (session time in µs, g) pairs, [forward] positive
 * speeding up, [right] positive to the right; null for a direction no accelerometer agrees on.
 */
class MeasuredG(val forward: List<Pair<Long, Double>>?, val right: List<Pair<Long, Double>>?)

/**
 * Everything the video export's data overlay draws (FBBD-152), looked up by session time: speed,
 * position, G, and the lap with its delta to the session's best lap.
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
    private val starts = LongArray(traces.size) { traces[it].lap.startMicros }
    private val alignments = HashMap<Int, LapAlignment>()
    private val hotlap: LapTrace? = hotlap?.let { n -> traces.firstOrNull { it.lap.number == n } }
    private val car: Map<CarValue, TimeSeries>

    /**
     * The car's gearbox as this session's engine speed and road speed show it (FBBD-397), for the
     * gear on the RPM gauge (UI-310 A); null without engine speed or when no known gearbox fits.
     */
    val gearbox: Gearbox?

    /** The car's values this session has samples of (FBBD-347): the overlay leaves out the rest. */
    val carValues: Set<CarValue> get() = car.keys

    /** The session's best lap time; null without one. */
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
        gearbox = ids[CarValue.Rpm]?.let { fitGearbox(listOf(driveSamples(sorted, it, speed))) }
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
     */
    fun trail(sessionMicros: Long): List<Xy?> {
        val lapStart = traces.lastOrNull { it.lap.startMicros <= sessionMicros && sessionMicros < it.lap.endMicros }
            ?.lap?.startMicros
        val start = lapStart ?: (sessionMicros - TrailFallbackMicros)
        val step = maxOf(TrailMinStepMicros, (sessionMicros - start) / TrailMaxPoints)
        val out = ArrayList<Xy?>()
        var open = false
        fun add(t: Long) {
            val px = x.at(t)
            val py = y.at(t)
            if (px != null && py != null) {
                out += Xy(px, py)
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
        val kmh = speed.at(t)
        val gear = if (rpm != null && kmh != null) gearbox?.gearAt(rpm, kmh / KmhPerMs) else null
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
        )
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
                OverlayLap(trace.lap.number, trace.lap.kind, lapMicros, deltaAt(trace, lapMicros, isBest), isBest)
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
                val delta = best?.takeIf { lap.kind == LapKind.Timed }?.let { lap.timeMicros - it.lap.timeMicros }
                OverlayLap(lap.number, lap.kind, lap.timeMicros, delta, isBest, LapPhase.Finished)
            }
            else -> {
                val lapMicros = t - lap.startMicros
                OverlayLap(lap.number, lap.kind, lapMicros, deltaAt(trace, lapMicros, isBest), isBest)
            }
        }
    }

    private fun deltaAt(trace: LapTrace, lapMicros: Long, isBest: Boolean): Long? {
        val reference = best
        return when {
            reference == null || trace.lap.kind != LapKind.Timed -> null
            isBest -> 0L
            else -> {
                val alignment = alignments.getOrPut(trace.lap.number) { LapAlignment(reference, trace) }
                // Time this lap took to get here minus the time the best lap took to the same place.
                lapMicros - alignment.referenceMicrosAt(lapMicros)
            }
        }
    }

    private companion object {
        /** About a third of a second in all: smooth enough to read, quick enough for a braking point. */
        const val AccelHalfWindowMicros = 150_000L
        const val TrailFallbackMicros = 30_000_000L
        const val TrailMinStepMicros = 100_000L
        const val TrailMaxPoints = 600L
    }
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
            if (v.channelId in byId && v.value.isFinite()) {
                samples.getOrPut(v.channelId) { ArrayList() } += s.tMicros to v.value
            }
        }
    }
    return ids.mapNotNull { (value, id) -> samples[id]?.let { value to TimeSeries.of(it, value.maxGapMicros) } }.toMap()
}
