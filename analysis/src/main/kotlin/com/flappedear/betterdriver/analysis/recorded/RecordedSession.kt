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
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.accel.AxisDirection
import com.flappedear.betterdriver.recorder.accel.AxisFit
import com.flappedear.betterdriver.recorder.accel.fitMount
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import java.util.Locale
import kotlin.math.abs

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
@Suppress("CyclomaticComplexMethod")
fun telemetrySessionOf(data: SessionData, startFinish: TrackLine?, finish: TrackLine? = null): TelemetrySession {
    val sets = data.sampleSets.sortedBy { it.tMicros }
    val origin = sets.firstOrNull()?.tMicros ?: 0L
    val times = HashMap<Int, MutableList<Double>>()
    val values = HashMap<Int, MutableList<Double>>()
    for (set in sets) {
        val t = (set.tMicros - origin) / MicrosPerSecond
        val lat = set.values.firstOrNull { it.channelId == GnssChannels.LAT.id }?.value
        val lon = set.values.firstOrNull { it.channelId == GnssChannels.LON.id }?.value
        val noFix = lat == 0.0 && lon == 0.0
        for (v in set.values) {
            val ts = times.getOrPut(v.channelId) { mutableListOf() }
            if (ts.isNotEmpty() && t <= ts.last()) continue
            ts += t
            val position = v.channelId == GnssChannels.LAT.id || v.channelId == GnssChannels.LON.id
            values.getOrPut(v.channelId) { mutableListOf() } += if (position && noFix) Double.NaN else v.value
        }
    }
    val channels = LinkedHashMap<String, TelemetryChannel>()
    for ((id, ts) in times) {
        val def = data.channels[id] ?: data.info.channels.firstOrNull { it.id == id } ?: continue
        val channelValues = values.getValue(id).toDoubleArray()
        channels[def.name] = TelemetryChannel(def.name, ts.toDoubleArray(), channelValues, def.unit, def.provenance)
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
 * The car's acceleration as [ForwardAcceleration] and [LeftAcceleration], from the first accelerometer
 * of [AccelerometerAxes] that agrees with the acceleration GPS shows over the whole of [session]: the
 * receiver's, else the phone's own (FBBD-218). Its mount is found from GPS ([fitMount]): turned round,
 * turned by 90°, standing on its side or tilted, the tilt taken off. Forward and left may come from
 * different accelerometers. A direction no accelerometer agrees on ([AxisFit.usable]) is left out, so
 * the readers fall back to GPS. Unless the receiver's X and Y gave both, every accelerometer's
 * agreement goes to the debug log, so a drive's log shows why.
 */
private fun correctedAccelerometer(session: TelemetrySession): List<TelemetryChannel> {
    val units = AccelerometerAxes
        .map { names -> names.mapNotNull { session.channels[it] } }
        .filter { it.isNotEmpty() }
    if (units.isEmpty()) return emptyList()
    val gps = sessionGpsG(session, AccelerometerFitStepSeconds).orEmpty()
    val forwardG = DoubleArray(gps.size) { gps[it].longitudinalG }
    val leftG = DoubleArray(gps.size) { gps[it].lateralG }
    val fits = units.map { axes ->
        val readings = axes.map { channel ->
            DoubleArray(gps.size) { smoothedAt(channel, gps[it].time, AccelerometerSmoothingSeconds) ?: Double.NaN }
        }
        axes to fitMount(readings, forwardG, leftG)
    }
    val forward = fits.firstNotNullOfOrNull { (axes, fit) -> fit?.forward?.takeIf { it.usable }?.let { axes to it } }
    val left = fits.firstNotNullOfOrNull { (axes, fit) -> fit?.left?.takeIf { it.usable }?.let { axes to it } }
    val receiverXy = forward?.let { isReceiverAxis(it, 0) } == true && left?.let { isReceiverAxis(it, 1) } == true
    if (gps.isNotEmpty() && !receiverXy) {
        val each = fits.joinToString("; ") { (axes, fit) ->
            val names = axes.joinToString("/") { it.name }
            "$names forward ${describe(axes, fit?.forward)} left ${describe(axes, fit?.left)}"
        }
        val forwardFrom = forward?.first?.first()?.name ?: "GPS"
        val leftFrom = left?.first?.first()?.name ?: "GPS"
        DebugLog.i("AccelFit", "$each: forward from $forwardFrom, left from $leftFrom")
    }
    return listOfNotNull(
        forward?.let { (axes, d) -> corrected(axes, ForwardAcceleration, d) },
        left?.let { (axes, d) -> corrected(axes, LeftAcceleration, d) },
    )
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
    val values = DoubleArray(base.sampleCount) { i ->
        for (k in axes.indices) {
            val c = axes[k]
            reading[k] = if (c.timestamps.contentEquals(base.timestamps)) {
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
