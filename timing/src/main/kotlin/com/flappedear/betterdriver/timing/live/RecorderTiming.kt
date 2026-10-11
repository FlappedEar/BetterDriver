package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.track.Track

/** The GNSS position in a recorder sample set, or null if it has none. */
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
    return if (lat.isFinite() && lon.isFinite()) Fix(tMicros, lat, lon) else null
}

/**
 * Feeds the recorder's live output into [LiveTiming] and hands each new state to [onState]. Runs
 * on the recorder's listener thread, so a timing failure never touches the recording (FBBD-69).
 */
class TimingListener(track: Track, private val onState: (LiveTimingState) -> Unit) : RecorderListener {
    val timing = LiveTiming(track)

    override fun onSamples(set: SampleSet) {
        val fix = set.toFix() ?: return
        onState(timing.update(fix))
    }
}

/** The start/finish line as the recorder's gate, for the session metadata and the VBO export. */
fun Track.startFinishGate(): Gate? = startFinish?.let {
    Gate("Start / Finish", it.left.lat, it.left.lon, it.right.lat, it.right.lon)
}
