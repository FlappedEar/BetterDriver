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
import com.flappedear.betterdriver.recorder.accel.AxisFit
import com.flappedear.betterdriver.recorder.accel.fitAxis
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

/**
 * Accelerometer channels (X, Y, Z) a recording may hold, in order of preference: the RaceBox's, then
 * a RaceChrono DIY receiver's. Both record g; the unit travels with the channel. X is forward and Y
 * across the car when mounted as the maker shows, but any axis may be the one that points forward.
 */
private val AccelerometerAxes = listOf(
    listOf(AccelerationX, AccelerationY, AccelerationZ),
    listOf(RcAccelerationX, RcAccelerationY, RcAccelerationZ),
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
    // The first accelerometer the recording has, all axes from the same receiver.
    val axes = AccelerometerAxes.firstOrNull { a -> a.any { it in channels } } ?: AccelerometerAxes.first()
    correctedAccelerometer(TelemetrySession(channels, positions), axes).forEach { channels[it.name] = it }
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
 * The accelerometer as [ForwardAcceleration] and [LeftAcceleration], each fitted to the acceleration
 * GPS shows over the whole of [session] ([fitAxis]): turned round when it runs against GPS (a
 * receiver mounted backwards, or a file with the opposite convention) and with the mount's tilt taken
 * off. Forward is the axis of [axes] that agrees best with GPS braking and accelerating, left the
 * best of the others for cornering, so a receiver mounted turned by 90° or standing on its side
 * still counts (FBBD-218: Arek's 2026-10-13 drive with a newly paired RaceBox fell back to GPS). An
 * axis that does not agree with GPS ([AxisFit.usable]) is left out, so the readers fall back to GPS.
 * None without an axis or GPS movement to fit it to. Anything but X forward and Y left goes to the
 * debug log with every axis's agreement, so a drive's log shows how the receiver sat.
 */
private fun correctedAccelerometer(session: TelemetrySession, axes: List<String>): List<TelemetryChannel> {
    val present = axes.mapNotNull { session.channels[it] }
    if (present.isEmpty()) return emptyList()
    val gps = sessionGpsG(session, AccelerometerFitStepSeconds).orEmpty()
    val forwardG = DoubleArray(gps.size) { gps[it].longitudinalG }
    val leftG = DoubleArray(gps.size) { gps[it].lateralG }
    val fits = present.map { channel ->
        val readings = DoubleArray(gps.size) {
            smoothedAt(channel, gps[it].time, AccelerometerSmoothingSeconds) ?: Double.NaN
        }
        AxisCandidate(channel, fitAxis(readings, forwardG), fitAxis(readings, leftG))
    }
    val forward = fits.mapNotNull { a -> a.forward?.takeIf { it.usable }?.let { a.channel to it } }
        .maxByOrNull { abs(it.second.correlation) }
    val left = fits.filter { it.channel !== forward?.first }
        .mapNotNull { a -> a.left?.takeIf { it.usable }?.let { a.channel to it } }
        .maxByOrNull { abs(it.second.correlation) }
    val measured = fits.any { it.forward != null || it.left != null }
    if (measured && (forward?.first?.name != axes[0] || left?.first?.name != axes[1])) {
        val each = fits.joinToString {
            "${it.channel.name} forward ${agreement(it.forward)} left ${agreement(it.left)}"
        }
        DebugLog.i(
            "AccelFit",
            "$each: forward from ${forward?.first?.name ?: "GPS"}, left from ${left?.first?.name ?: "GPS"}",
        )
    }
    return listOfNotNull(
        forward?.let { (c, fit) -> corrected(c, ForwardAcceleration, fit) },
        left?.let { (c, fit) -> corrected(c, LeftAcceleration, fit) },
    )
}

/** One accelerometer axis and how it agrees with GPS speed changes ([forward]) and cornering ([left]). */
private class AxisCandidate(val channel: TelemetryChannel, val forward: AxisFit?, val left: AxisFit?)

private fun agreement(fit: AxisFit?) = fit?.let { "%.2f".format(Locale.ROOT, it.correlation) } ?: "-"

/** The step the accelerometer is compared with GPS at: fine enough for a braking point. */
private const val AccelerometerFitStepSeconds = 0.1

/** [channel] as [name], corrected by [fit]. */
private fun corrected(channel: TelemetryChannel, name: String, fit: AxisFit): TelemetryChannel {
    val values = DoubleArray(channel.sampleCount) { fit.correct(channel.values[it]) }
    // The values are still the accelerometer's, turned round and levelled.
    return TelemetryChannel(name, channel.timestamps, values, channel.unit, channel.provenance)
}
