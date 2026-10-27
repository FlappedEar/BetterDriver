package com.flappedear.betterdriver.recorder.export

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet

/**
 * One lap of a session for CSV and GPX export (FBBD-150), on the session clock: from [startMicros]
 * up to, not including, [endMicros]. The recorder does not time laps; the app replays them
 * (timing.replay) and hands them in, so the recorder never depends on the timing module.
 * A lap still being driven when the session stopped ends at [Long.MAX_VALUE].
 */
data class ExportLap(val number: Int, val startMicros: Long, val endMicros: Long) {
    init {
        require(endMicros > startMicros) { "lap $number ends before it starts" }
    }
}

/**
 * Finds the lap of each time, for times given in increasing order: one pass over the laps for a
 * whole session, however long. Laps are taken in start order and must not overlap.
 */
internal class LapCursor(laps: List<ExportLap>) {
    private val laps = laps.sortedBy { it.startMicros }
    private var i = 0

    /** The lap holding [tMicros], or null when it falls before, between or after the laps. */
    fun at(tMicros: Long): ExportLap? {
        while (i < laps.size && tMicros >= laps[i].endMicros) i++
        val lap = laps.getOrNull(i) ?: return null
        return lap.takeIf { tMicros >= it.startMicros }
    }
}

/** A position of a sample set; null without one. Receivers without a fix can report 0, 0. */
internal class Position(val lat: Double, val lon: Double)

internal fun SampleSet.position(): Position? {
    var lat = Double.NaN
    var lon = Double.NaN
    for (v in values) {
        when (v.channelId) {
            GnssChannels.LAT.id -> lat = v.value
            GnssChannels.LON.id -> lon = v.value
        }
    }
    val valid = lat in -LatLimit..LatLimit && lon in -LonLimit..LonLimit && !(lat == 0.0 && lon == 0.0)
    return if (valid) Position(lat, lon) else null
}

/** The value of [channelId] in this set, if it holds a finite one. */
internal fun SampleSet.valueOf(channelId: Int): Double? =
    values.firstOrNull { it.channelId == channelId }?.value?.takeIf { it.isFinite() }

/** True when the set holds a latitude and a longitude, valid or not: the receiver reported a fix attempt. */
internal fun SampleSet.reportsPosition() =
    values.any { it.channelId == GnssChannels.LAT.id } && values.any { it.channelId == GnssChannels.LON.id }

private const val LatLimit = 90.0
private const val LonLimit = 180.0
