package com.flappedear.betterdriver.analysis.recorded

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.track.TrackLine

private const val MicrosPerSecond = 1_000_000.0

/**
 * A recorded BetterDriver session as analysis input (FBBD-102). Every recorded channel becomes one
 * analysis channel holding the samples that carry it, in time order, with times in seconds from
 * the session's first sample. A sample at the same time as the one before is dropped. A position
 * of exactly 0°, 0° (no fix) is no data. [startFinish] (the track's start/finish line) becomes
 * the start gate; without one no laps are found.
 */
@Suppress("CyclomaticComplexMethod")
fun telemetrySessionOf(data: SessionData, startFinish: TrackLine?): TelemetrySession {
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
    val aliases = mapOf(
        Aliases.LATITUDE to GnssChannels.LAT.name,
        Aliases.LONGITUDE to GnssChannels.LON.name,
        Aliases.SPEED to GnssChannels.SPEED_KMH.name,
    ).filterValues { it in channels }
    val gates = listOfNotNull(
        startFinish?.let {
            TimingGate(
                TimingGateType.Start,
                "Start/finish",
                GeoCoordinate(it.left.lat, it.left.lon),
                GeoCoordinate(it.right.lat, it.right.lon),
            )
        },
    )
    return TelemetrySession(channels, aliases, gates)
}
