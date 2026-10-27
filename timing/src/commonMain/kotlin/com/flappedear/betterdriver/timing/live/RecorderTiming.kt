package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.detectTrack
import kotlin.concurrent.Volatile

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
 * fixed for the outing. [onDetected] runs once, on the listener thread. [onLap] gets each finished
 * lap with the predicted lap time the track screen showed on the fix before its line, for the
 * session's validation report (FBBD-183).
 *
 * Runs on the recorder's listener thread; [detection] and [state] can be read from any thread.
 */
class SessionTiming(
    private val tracks: () -> List<Track>,
    private val directory: CircuitList,
    private val onLap: (lap: Lap, predictedMillis: Long?) -> Unit = { _, _ -> },
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
            val found = detectTrack(GeoPoint(fix.lat, fix.lon), tracks(), directory)
            val track = (found as? TrackDetection.Saved)?.track?.takeIf { it.timeable }
            timing = track?.let { LiveTiming(it) }
            state = timing?.state
            detection = found
            onDetected(found)
        }
        timing?.let { t ->
            val shown = state?.predictedMillis
            val before = t.book.count
            state = t.update(fix)
            if (t.book.count > before) t.book.laps.drop(before).forEach { onLap(it, shown) }
        }
    }
}

/** The text of a [com.flappedear.betterdriver.recorder.EventKinds.LAP_LINE] event. */
fun lapLineText(lap: Lap, predictedMillis: Long?): String =
    "lap=${lap.number} kind=${lap.kind.name} time_ms=${lap.timeMillis} predicted_ms=${predictedMillis ?: "none"}" +
        if (lap.gnssGap) " gnss_gap=true" else ""
