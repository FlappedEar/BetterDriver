package com.flappedear.betterdriver.analysis.recorded

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.map.AccelerometerSmoothingSeconds
import com.flappedear.betterdriver.analysis.map.sessionGpsG
import com.flappedear.betterdriver.analysis.map.smoothedAt
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.accel.AxisDirection
import com.flappedear.betterdriver.recorder.accel.AxisFit
import com.flappedear.betterdriver.recorder.accel.DirectionReading
import com.flappedear.betterdriver.recorder.accel.MountFit
import com.flappedear.betterdriver.recorder.accel.WindowMeans
import com.flappedear.betterdriver.recorder.accel.fitMount
import com.flappedear.betterdriver.recorder.accel.fuseDirection
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MicrosPerSecond = 1_000_000.0

/**
 * Receivers' horizontal position accuracy in metres, by preference: the RaceBox's
 * (RaceBoxChannels.H_ACC) and the Dragy's (DragyChannels.H_ACC). Other receivers report none in metres.
 */
private val HorizontalAccuracy = listOf("gnss_hacc", "dragy_hacc")

/** The RaceBox's acceleration along its X axis, which the mount points forward (RaceBoxChannels.ACCEL_X). */
private const val AccelerationX = "accel_x"

/** The RaceBox's acceleration along its Y axis, across the car (RaceBoxChannels.ACCEL_Y). */
private const val AccelerationY = "accel_y"

/** The RaceBox's acceleration along its Z axis, up out of its face when mounted flat (RaceBoxChannels.ACCEL_Z). */
private const val AccelerationZ = "accel_z"

/** The RaceChrono DIY receivers' (NMEA and TCP) accelerations, in g like the RaceBox's (RcChannels). */
private const val RcAccelerationX = "rc_accel_x"
private const val RcAccelerationY = "rc_accel_y"
private const val RcAccelerationZ = "rc_accel_z"

/** The phone's own accelerometer, recorded in every session since FBBD-218 (PhoneImuChannels). */
private val PhoneAccelerometer = listOf("phone_accel_x", "phone_accel_y", "phone_accel_z")

/**
 * Accelerometers (X, Y, Z channels) a recording may hold, in order of preference: the RaceBox's, a
 * RaceChrono DIY receiver's, then the phone's own. All record g; the unit travels with the channel.
 * Any of them may sit in any way in the car ([fitMount]).
 */
private val AccelerometerAxes = listOf(
    listOf(AccelerationX, AccelerationY, AccelerationZ),
    listOf(RcAccelerationX, RcAccelerationY, RcAccelerationZ),
    PhoneAccelerometer,
)

/**
 * The accelerometer's X axis signed so that speeding up is positive, as the Corner Analyzer reads it,
 * with the mount's tilt taken off (see [AxisFit]).
 */
const val ForwardAcceleration = "accel_forward"

/** The accelerometer's Y axis signed so that a left turn is positive, as the G-G diagram reads it, tilt taken off. */
const val LeftAcceleration = "accel_left"

/**
 * A recorded BetterDriver session as analysis input (FBBD-102). Every recorded channel becomes one
 * analysis channel holding the samples that carry it, in time order, with times in seconds from
 * the session's first sample. A sample at the same time as the one before is dropped. A position
 * of exactly 0°, 0° (no fix) is no data. [startFinish] (the track's start/finish line) becomes
 * the start gate; without one no laps are found. [finish], a point-to-point track's finish line
 * (FBBD-155), becomes a finish gate, and the laps are then the runs from start to finish.
 */
fun telemetrySessionOf(data: SessionData, startFinish: TrackLine?, finish: TrackLine? = null): TelemetrySession =
    telemetrySessionOf(data, startFinish, finish) { true }

/**
 * As [telemetrySessionOf], with only the recorded channels [keep] accepts: the VBO's G columns need
 * the accelerometers and GPS, not an hour of every other channel in memory as well (FBBD-232).
 */
@Suppress("CyclomaticComplexMethod")
fun telemetrySessionOf(
    data: SessionData,
    startFinish: TrackLine?,
    finish: TrackLine?,
    keep: (ChannelDef) -> Boolean,
): TelemetrySession {
    val sets = data.sampleSets.sortedBy { it.tMicros }
    val origin = sets.firstOrNull()?.tMicros ?: 0L
    val defs = HashMap<Int, ChannelDef?>()
    fun defOf(id: Int) = defs.getOrPut(id) {
        (data.channels[id] ?: data.info.channels.firstOrNull { it.id == id })?.takeIf(keep)
    }
    // Primitive arrays, not boxed lists: an hour holds millions of values (FBBD-232).
    val columns = LinkedHashMap<Int, Samples>()
    for (set in sets) {
        val t = (set.tMicros - origin) / MicrosPerSecond
        val lat = set.values.firstOrNull { it.channelId == GnssChannels.LAT.id }?.value
        val lon = set.values.firstOrNull { it.channelId == GnssChannels.LON.id }?.value
        val noFix = lat == 0.0 && lon == 0.0
        for (v in set.values) {
            if (defOf(v.channelId) == null) continue
            val column = columns.getOrPut(v.channelId) { Samples() }
            if (column.size > 0 && t <= column.lastTime) continue
            val position = v.channelId == GnssChannels.LAT.id || v.channelId == GnssChannels.LON.id
            column.add(t, if (position && noFix) Double.NaN else v.value)
        }
    }
    val channels = LinkedHashMap<String, TelemetryChannel>()
    for ((id, column) in columns) {
        val def = defOf(id) ?: continue
        channels[def.name] = TelemetryChannel(def.name, column.times(), column.values(), def.unit, def.provenance)
    }
    val positions = mapOf(
        Aliases.LATITUDE to GnssChannels.LAT.name,
        Aliases.LONGITUDE to GnssChannels.LON.name,
        Aliases.SPEED to GnssChannels.SPEED_KMH.name,
        Aliases.ACCURACY to HorizontalAccuracy.firstOrNull { it in channels }.orEmpty(),
    ).filterValues { it in channels }
    correctedAccelerometer(TelemetrySession(channels, positions)).forEach { channels[it.name] = it }
    val aliases = positions + mapOf(
        Aliases.LONGITUDINAL_ACCELERATION to ForwardAcceleration,
        Aliases.LATERAL_ACCELERATION to LeftAcceleration,
    ).filterValues { it in channels }
    val gates = listOfNotNull(
        startFinish?.let { gate(TimingGateType.Start, "Start/finish", it) },
        finish?.let { gate(TimingGateType.Finish, "Finish", it) },
    )
    return TelemetrySession(channels, aliases, gates)
}

/**
 * [data] as analysis input with only what the car's corrected G needs ([correctedAccelerometer]):
 * GNSS and the accelerometers. No laps.
 */
fun accelerationSessionOf(data: SessionData): TelemetrySession = telemetrySessionOf(data, null, null) { def ->
    def.id in GnssChannels.IDS || def.name in HorizontalAccuracy || AccelerometerAxes.any { def.name in it }
}

/** One channel's samples as they are collected, in arrays that grow by half. */
private class Samples {
    private var t = DoubleArray(InitialSamples)
    private var v = DoubleArray(InitialSamples)
    var size = 0
        private set
    val lastTime: Double get() = t[size - 1]

    fun add(time: Double, value: Double) {
        if (size == t.size) {
            val grown = size + size / 2
            t = t.copyOf(grown)
            v = v.copyOf(grown)
        }
        t[size] = time
        v[size] = value
        size++
    }

    fun times(): DoubleArray = t.copyOf(size)

    fun values(): DoubleArray = v.copyOf(size)
}

private const val InitialSamples = 1024

/** [track]'s session as analysis input: its start/finish line and, on a point-to-point track, its finish. */
fun telemetrySessionOf(data: SessionData, track: Track): TelemetrySession =
    telemetrySessionOf(data, track.startFinish, track.finish?.takeIf { track.pointToPoint })

/** A BetterDriver line as an analysis gate: endpoint A is its left end, so forwards is direction +1. */
private fun gate(type: TimingGateType, name: String, line: TrackLine) = TimingGate(
    type,
    name,
    GeoCoordinate(line.left.lat, line.left.lon),
    GeoCoordinate(line.right.lat, line.right.lon),
)

/**
 * The car's acceleration as [ForwardAcceleration] and [LeftAcceleration], from every accelerometer of
 * [AccelerometerAxes] that agrees with the acceleration GPS shows over the whole of [session]: the
 * receiver's and the phone's own (FBBD-218), averaged when both do (FBBD-226, [fuseDirection]), as
 * RaceChrono does. Each mount is found from GPS ([fitMount]): turned round, turned by 90°, standing
 * on its side or tilted, the tilt taken off. Forward and left may come from different
 * accelerometers. A direction no accelerometer agrees on ([AxisFit.usable]) is left out, so the
 * readers fall back to GPS. Unless the receiver's X and Y alone gave both, every accelerometer's
 * agreement and share goes to the debug log, so a drive's log shows why.
 */
private fun correctedAccelerometer(session: TelemetrySession): List<TelemetryChannel> {
    val units = AccelerometerAxes
        .map { names -> names.mapNotNull { session.channels[it] } }
        .filter { it.isNotEmpty() }
    if (units.isEmpty()) return emptyList()
    val gps = sessionGpsG(session, AccelerometerFitStepSeconds).orEmpty()
    val forwardG = DoubleArray(gps.size) { gps[it].longitudinalG }
    val leftG = DoubleArray(gps.size) { gps[it].lateralG }
    val gpsTimes = DoubleArray(gps.size) { gps[it].time }
    val aligned = units.map { axes -> axes to alignedMount(axes, gpsTimes, forwardG, leftG) }
    val fits = aligned.map { (axes, a) -> axes to a.first }
    // How late each accelerometer's clock reads against GPS, by its first channel's name.
    val lateness = aligned.associate { (axes, a) -> axes.first().name to a.second }
    val forward = fits.mapNotNull { (axes, fit) -> fit?.forward?.takeIf { it.usable }?.let { axes to it } }
    val left = fits.mapNotNull { (axes, fit) -> fit?.left?.takeIf { it.usable }?.let { axes to it } }
    val forwardGps = DirectionReading("GPS", gpsTimes, forwardG, Double.NaN)
    val forwardFused = fused(forward, ForwardAcceleration, forwardGps, lateness)
    val leftFused = fused(left, LeftAcceleration, DirectionReading("GPS", gpsTimes, leftG, Double.NaN), lateness)
    val receiverXy = forward.size == 1 && isReceiverAxis(forward.single(), 0) &&
        left.size == 1 && isReceiverAxis(left.single(), 1)
    if (gps.isNotEmpty() && !receiverXy) {
        val each = fits.joinToString("; ") { (axes, fit) ->
            val names = axes.joinToString("/") { it.name }
            "$names forward ${describe(axes, fit?.forward)} left ${describe(axes, fit?.left)}"
        }
        DebugLog.i(
            "AccelFit",
            "$each: forward from ${forwardFused?.second ?: "GPS"}, left from ${leftFused?.second ?: "GPS"}",
        )
    }
    return listOfNotNull(forwardFused?.first, leftFused?.first)
}

/**
 * How [axes] sit in the car ([fitMount]) against GPS's [forwardG] and [leftG] at [times], with the
 * accelerometer read up to [MaxMountLagSeconds] later or earlier: the lag where both directions
 * agree with GPS best, and that lag (seconds the accelerometer's stamps are late). A phone stamped
 * apart from the receiver then is not taken for one mounted across the car, or left out, before
 * [fuseDirection] matches the clocks finely.
 */
private fun alignedMount(
    axes: List<TelemetryChannel>,
    times: DoubleArray,
    forwardG: DoubleArray,
    leftG: DoubleArray,
): Pair<MountFit?, Double> {
    val steps = (MaxMountLagSeconds / MountLagStepSeconds).roundToInt()
    // Lag 0 first, so a tie keeps the clock as recorded.
    val lags = (listOf(0) + (1..steps).flatMap { listOf(it, -it) }).map { it * MountLagStepSeconds }
    val windows = axes.map { WindowMeans(it.timestamps, it.values) }
    return lags.map { lag ->
        val readings = axes.mapIndexed { k, channel ->
            // As smoothedAt, in one sweep; an empty window falls back to it.
            val means = windows[k].at(times, AccelerometerSmoothingSeconds, lag)
            DoubleArray(times.size) { i ->
                means[i].takeIf { it.isFinite() }
                    ?: smoothedAt(channel, times[i] + lag, AccelerometerSmoothingSeconds)
                    ?: Double.NaN
            }
        }
        fitMount(readings, forwardG, leftG) to lag
    }.maxBy { (fit, _) ->
        fun agreement(d: AxisDirection?) = d?.fit?.correlation?.let(::abs) ?: 0.0
        if (fit == null) -1.0 else agreement(fit.forward) + agreement(fit.left)
    }
}

/**
 * The receivers' accelerometers share one clock with their own GNSS, so a lag against GPS-derived
 * G is that G's own; only the phone's is a clock of its own to be moved onto the session's.
 */
private fun ownClock(name: String) = name in PhoneAccelerometer

/** As far as [fuseDirection] matches clocks. */
private const val MaxMountLagSeconds = 0.3

/** Coarse: [fuseDirection] matches the clocks to 10 ms; the mount only needs them near. */
private const val MountLagStepSeconds = 0.05

/**
 * The accelerometers [found] for one direction as one channel [name]: the one alone at its own
 * times, or several averaged ([fuseDirection], weighed against [gps] with each one's clock
 * matched); with how it was made, for the debug log, e.g.
 * "accel_x 0.64 + phone_accel_x 0.36 (+0.05 s)". When the clock kept is the phone's (no receiver's
 * accelerometer agrees), it is moved by its [lateness] against GPS onto the session's clock.
 */
private fun fused(
    found: List<Pair<List<TelemetryChannel>, AxisDirection>>,
    name: String,
    gps: DirectionReading,
    lateness: Map<String, Double>,
): Pair<TelemetryChannel, String>? {
    val channels = found.map { (axes, d) -> corrected(axes, name, d) to d }
    fun onSessionClock(c: TelemetryChannel, kept: String): TelemetryChannel {
        val late = if (ownClock(kept)) lateness[kept] ?: 0.0 else 0.0
        if (late == 0.0) return c
        val times = DoubleArray(c.sampleCount) { c.timestamps[it] - late }
        return TelemetryChannel(c.name, times, c.values, c.unit, c.provenance)
    }
    if (channels.size <= 1) {
        return channels.firstOrNull()?.let { (c, _) ->
            val unit = found.single().first.first().name
            onSessionClock(c, unit) to unit
        }
    }
    val readings = channels.mapIndexed { k, (c, d) ->
        DirectionReading(found[k].first.first().name, c.timestamps, c.values, d.fit.residual)
    }
    val base = channels.first().first
    return fuseDirection(readings, gps)?.let { result ->
        val how = result.shares.joinToString(" + ") { s ->
            val lag = if (s.lagSeconds != 0.0) " (${"%+.2f".format(Locale.ROOT, s.lagSeconds)} s)" else ""
            "${s.name} ${"%.2f".format(Locale.ROOT, s.weight)}$lag"
        } + result.left.joinToString("") { ", $it left out" }
        // The clock kept: the first reading's when averaged in, else the best one's (lag 0 either way).
        val kept = readings.map { it.name }.firstOrNull { n ->
            result.shares.any { it.name == n && it.lagSeconds == 0.0 }
        } ?: readings.first().name
        // Still accelerometer readings, turned to the car, levelled and averaged.
        onSessionClock(TelemetryChannel(name, result.times, result.values, base.unit, base.provenance), kept) to how
    }
}

/** Whether [found] is the first accelerometer's axis [index] alone (X forward, Y left), as mounted by the book. */
private fun isReceiverAxis(found: Pair<List<TelemetryChannel>, AxisDirection>, index: Int): Boolean {
    val (axes, d) = found
    return axes.firstOrNull()?.name == AccelerometerAxes.first().first() &&
        d.weights.indices.all { k -> if (k == index) abs(d.weights[k]) > AlignedWeight else true }
}

/** A direction whose weight on one axis is above this is that axis alone (within 10°). */
private const val AlignedWeight = 0.985

/** The agreement with GPS and the direction, e.g. "0.93 (accel_x -0.99, accel_y 0.10, accel_z 0.05)". */
private fun describe(axes: List<TelemetryChannel>, d: AxisDirection?): String = d?.let {
    val weights = axes.indices.joinToString { k ->
        "${axes[k].name} ${"%.2f".format(Locale.ROOT, it.weights[k] * it.fit.sign)}"
    }
    "${"%.2f".format(Locale.ROOT, it.fit.correlation)} ($weights)"
} ?: "-"

/** The step the accelerometer is compared with GPS at: fine enough for a braking point. */
private const val AccelerometerFitStepSeconds = 0.1

/**
 * The accelerometer [axes] along direction [d], corrected, as [name], at the first axis's times.
 * The axes of one receiver come in the same sample sets; an axis with other times is read at the
 * first's times.
 */
private fun corrected(axes: List<TelemetryChannel>, name: String, d: AxisDirection): TelemetryChannel {
    val base = axes.first()
    val reading = DoubleArray(axes.size)
    // Compared once per axis. Compared at every sample, the phone's 110 samples a second cost tens of
    // billions of comparisons on a half-hour drive once 0.3.19 averaged the phone in.
    val sameTimes = axes.map { it.timestamps.contentEquals(base.timestamps) }
    val values = DoubleArray(base.sampleCount) { i ->
        for (k in axes.indices) {
            val c = axes[k]
            reading[k] = if (sameTimes[k]) {
                c.values[i]
            } else {
                smoothedAt(c, base.timestamps[i], OtherAxisHalfWindowSeconds) ?: Double.NaN
            }
        }
        d.correct(reading)
    }
    // The values are still the accelerometer's, turned to the car and levelled.
    return TelemetryChannel(name, base.timestamps, values, base.unit, base.provenance)
}

/** How far from a sample another axis's reading may be taken: half a 25 Hz step. */
private const val OtherAxisHalfWindowSeconds = 0.02
