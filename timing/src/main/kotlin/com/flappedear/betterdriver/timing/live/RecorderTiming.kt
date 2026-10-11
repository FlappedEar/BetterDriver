package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.detectTrack

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

/**
 * Live timing for a whole recording (FBBD-55, FBBD-12). At the first position it picks the track
 * from [tracks] (read then, so lines saved just before the outing count) and [directory]; if that
 * is a saved track with a start/finish line, every later fix is timed against it. The result is
 * fixed for the outing. [onDetected] runs once, on the listener thread.
 *
 * Runs on the recorder's listener thread; [detection] and [state] can be read from any thread.
 */
class SessionTiming(
    private val tracks: () -> List<Track>,
    private val directory: CircuitList,
    private val onDetected: (TrackDetection) -> Unit = {},
) : RecorderListener {
    @Volatile
    var detection: TrackDetection? = null
        private set

    /** Null until a saved track with lines is detected. */
    @Volatile
    var state: LiveTimingState? = null
        private set

    private var timing: LiveTiming? = null

    override fun onSamples(set: SampleSet) {
        val fix = set.toFix() ?: return
        if (detection == null) {
            // Receivers without a fix can report 0, 0; that is never a track.
            if (fix.lat == 0.0 && fix.lon == 0.0) return
            val found = detectTrack(GeoPoint(fix.lat, fix.lon), tracks(), directory)
            val track = (found as? TrackDetection.Saved)?.track?.takeIf { it.timeable }
            timing = track?.let { LiveTiming(it) }
            state = timing?.state
            detection = found
            onDetected(found)
        }
        timing?.let { state = it.update(fix) }
    }
}

/** The track's name for screens: a saved track, or the directory circuit the car is at. */
val TrackDetection.placeName: String?
    get() = when (this) {
        is TrackDetection.Saved -> track.displayName
        is TrackDetection.Directory -> circuit.name
        TrackDetection.Unknown -> null
    }

/** The start/finish line as the recorder's gate, for the session metadata and the VBO export. */
fun Track.startFinishGate(): Gate? = startFinish?.let {
    Gate("Start / Finish", it.left.lat, it.left.lon, it.right.lat, it.right.lon)
}

/** Kind of the session event that records where a recording was (FBBD-55). */
const val TrackEventKind = "track"

/** The session's record of where it was: "saved <id> rev <n> <name>", "circuit <id> <name>" or "unknown". */
fun TrackDetection.eventText(): String = when (this) {
    is TrackDetection.Saved -> "saved ${track.id} rev ${track.revision} ${track.displayName}"
    is TrackDetection.Directory -> "circuit ${circuit.id} ${circuit.name}"
    TrackDetection.Unknown -> "unknown"
}

/** The saved track's id in a [TrackEventKind] event's text, or null when it was not at a saved track. */
fun savedTrackId(eventText: String): String? {
    val parts = eventText.split(' ')
    return if (parts.size >= 2 && parts[0] == "saved") parts[1] else null
}
