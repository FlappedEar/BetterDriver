package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet

/**
 * The GNSS position in a recorder sample set, or null if it has none. Receivers without a fix can
 * report 0, 0; that is no position either, so live timing and replay see the same fixes.
 */
fun SampleSet.toFix(): Fix? {
    var lat: Double? = null
    var lon: Double? = null
    for (v in values) {
        when (v.channelId) {
            GnssChannels.LAT.id -> lat = v.value
            GnssChannels.LON.id -> lon = v.value
        }
    }
    if (lat == null || lon == null) return null
    val noFix = lat == 0.0 && lon == 0.0
    return if (lat.isFinite() && lon.isFinite() && !noFix) Fix(tMicros, lat, lon) else null
}
