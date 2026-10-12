package com.flappedear.betterdriver.analysis.recorded

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine

private const val MicrosPerSecond = 1_000_000.0

/**
 * Receivers' horizontal position accuracy in metres, by preference: the RaceBox's
 * (RaceBoxChannels.H_ACC) and the Dragy's (DragyChannels.H_ACC). Other receivers report none in metres.
 */
private val HorizontalAccuracy = listOf("gnss_hacc", "dragy_hacc")

/** The RaceBox's acceleration along its X axis, which the mount points forward (RaceBoxChannels.ACCEL_X). */
private const val AccelerationX = "accel_x"

/**
 * The RaceBox's acceleration along its Y axis, across the car (RaceBoxChannels.ACCEL_Y). Its sign is
 * left as recorded: the G-G diagram, its only reader, checks it against the GPS turn.
 */
private const val AccelerationY = "accel_y"

/** The RaceChrono DIY receivers' (NMEA and TCP) accelerations, in g like the RaceBox's (RcChannels). */
private const val RcAccelerationX = "rc_accel_x"
private const val RcAccelerationY = "rc_accel_y"

/**
 * Accelerometer channel pairs (forward X, sideways Y) a recording may hold, in order of preference:
 * the RaceBox's, then a RaceChrono DIY receiver's. Both record g; the unit travels with the channel.
 */
private val AccelerometerAxes = listOf(AccelerationX to AccelerationY, RcAccelerationX to RcAccelerationY)

/** The accelerometer's X axis signed so that speeding up is positive, as the Corner Analyzer reads it. */
const val ForwardAcceleration = "accel_forward"

/** Half the span of the speed difference the acceleration's sign is checked against. */
private const val SignCheckHalfSeconds = 0.5

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
        channels[def.name] = TelemetryChannel(def.name, ts.toDoubleArray(), channelValues, def.unit)
    }
    // The first accelerometer the recording has, X and Y from the same receiver.
    val axes = AccelerometerAxes.firstOrNull { (x, _) -> x in channels } ?: AccelerometerAxes.first()
    forwardAcceleration(channels, axes.first)?.let { channels[it.name] = it }
    val aliases = mapOf(
        Aliases.LATITUDE to GnssChannels.LAT.name,
        Aliases.LONGITUDE to GnssChannels.LON.name,
        Aliases.SPEED to GnssChannels.SPEED_KMH.name,
        Aliases.ACCURACY to HorizontalAccuracy.firstOrNull { it in channels }.orEmpty(),
        Aliases.LONGITUDINAL_ACCELERATION to ForwardAcceleration,
        Aliases.LATERAL_ACCELERATION to axes.second,
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
 * Channel [name] (an accelerometer's X axis) as [ForwardAcceleration], or null without it or a
 * speed. Its sign is taken from the recording itself: the channel is negated when it runs against
 * the change in GPS speed, so a receiver mounted backwards, or reporting the opposite convention,
 * still reads braking as negative.
 */
private fun forwardAcceleration(channels: Map<String, TelemetryChannel>, name: String): TelemetryChannel? {
    val x = channels[name]
    val speed = channels[GnssChannels.SPEED_KMH.name]
    if (x == null || speed == null) return null
    var agreement = 0.0
    for (i in x.timestamps.indices) {
        val t = x.timestamps[i]
        val before = speed.valueAt(t - SignCheckHalfSeconds)
        val after = speed.valueAt(t + SignCheckHalfSeconds)
        if (before != null && after != null && x.values[i].isFinite()) agreement += x.values[i] * (after - before)
    }
    val values = if (agreement < 0.0) DoubleArray(x.values.size) { -x.values[it] } else x.values.copyOf()
    return TelemetryChannel(ForwardAcceleration, x.timestamps, values, x.unit)
}
