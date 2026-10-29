package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.detectTrack
import com.flappedear.betterdriver.timing.track.findSessionLoop
import com.flappedear.betterdriver.timing.track.proposeTrack
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
 * With no timeable track there (a new place, or a track saved without its line), it watches for a
 * closed loop driven again and again (FBBD-335): once [findSessionLoop] finds one, a provisional
 * start/finish goes across the middle of its longest straight, the fixes so far are timed through
 * it and timing goes on live, so the first session at a new track has laps and delta. The line is
 * never saved here; [onProvisional] gets the track it made, once, and the screens after the
 * session still offer to make a track from it (FBBD-151).
 *
 * Runs on the recorder's listener thread; [detection], [provisional] and [state] can be read from
 * any thread.
 */
class SessionTiming(
    private val tracks: () -> List<Track>,
    private val directory: CircuitList,
    private val onLap: (lap: Lap, predictedMillis: Long?) -> Unit = { _, _ -> },
    private val onProvisional: (Track) -> Unit = {},
    private val onDetected: (TrackDetection) -> Unit = {},
) : RecorderListener {
    @Volatile
    var detection: TrackDetection? = null
        private set

    /** The track with the provisional line, once the loop is found; null at a saved track with lines. */
    @Volatile
    var provisional: Track? = null
        private set

    /** Null until a saved track with lines is detected, or a provisional line is placed. */
    @Volatile
    var state: LiveTimingState? = null
        private set

    private var timing: LiveTiming? = null

    // Fixes kept while looking for a loop: one per KeepEveryMicros, at most MaxKeptFixes.
    private var looking: LoopSearch? = null

    override fun onSamples(set: SampleSet) {
        val fix = set.toFix() ?: return
        if (detection == null) {
            val saved = tracks()
            val found = detectTrack(GeoPoint(fix.lat, fix.lon), saved, directory)
            val track = (found as? TrackDetection.Saved)?.track?.takeIf { it.timeable }
            timing = track?.let { LiveTiming(it) }
            state = timing?.state
            // A point-to-point track without its finish is left alone, as proposeTrack does.
            val pointToPoint = (found as? TrackDetection.Saved)?.track?.pointToPoint == true
            if (track == null && !pointToPoint) looking = LoopSearch(saved, directory)
            detection = found
            onDetected(found)
        }
        timing?.let { t -> state = time(t, fix, state?.predictedMillis) }
        looking?.let { search ->
            val made = search.offer(fix) ?: return
            looking = null
            val t = LiveTiming(made)
            // The laps so far, the last fix included, as if the line had been there from the start;
            // none of them was predicted.
            for (kept in search.fixes) time(t, kept, shown = null)
            timing = t
            state = t.state
            provisional = made
            onProvisional(made)
        }
    }

    /** Times [fix] with [t], reporting each lap it finishes with [shown]; the new state. */
    private fun time(t: LiveTiming, fix: Fix, shown: Long?): LiveTimingState {
        val before = t.book.count
        val next = t.update(fix)
        if (t.book.count > before) t.book.laps.drop(before).forEach { onLap(it, shown) }
        return next
    }
}

/**
 * Looks for the loop of a session at a place with no line (FBBD-335). Keeps the fixes at
 * [KeepEveryMicros] apart (enough for [findSessionLoop], whose grid is metres apart, and for
 * timing laps of a minute or more), and searches again each [SearchEveryMicros] of driving over
 * the last [SearchWindowMicros], so a long road drive costs a bounded search now and then.
 */
private class LoopSearch(private val saved: List<Track>, private val directory: CircuitList) {
    val fixes = ArrayDeque<Fix>()
    private var nextSearchMicros = Long.MIN_VALUE

    /** Takes [fix]; the track with a provisional line once the loop is found, else null. */
    fun offer(fix: Fix): Track? {
        val last = fixes.lastOrNull()
        val kept = last == null || fix.tMicros - last.tMicros >= KeepEveryMicros
        if (kept) {
            fixes.addLast(fix)
            if (fixes.size > MaxKeptFixes) fixes.removeFirst()
            if (nextSearchMicros == Long.MIN_VALUE) nextSearchMicros = fix.tMicros + SearchEveryMicros
        }
        if (!kept || fix.tMicros < nextSearchMicros) return null
        nextSearchMicros = fix.tMicros + SearchEveryMicros
        val from = fix.tMicros - SearchWindowMicros
        return findSessionLoop(fixes.filter { it.tMicros >= from })
            ?.let { proposeTrack(it, saved, directory, ProvisionalName, id = ProvisionalId) }
    }
}

/** Name and id of a track with a provisional line: it lives only in the recording that made it. */
const val ProvisionalName = "New track"
const val ProvisionalId = "provisional"

// Just under 0.1 s, so a 10 Hz receiver whose fixes come a little early keeps every one.
private const val KeepEveryMicros = 90_000L
private const val SearchEveryMicros = 10_000_000L
private const val SearchWindowMicros = 20L * 60 * 1_000_000

// A day of fixes at 10 Hz: memory stays bounded however long the recording runs.
private const val MaxKeptFixes = 10 * 60 * 60 * 6

/** The text of a [com.flappedear.betterdriver.recorder.EventKinds.LAP_LINE] event. */
fun lapLineText(lap: Lap, predictedMillis: Long?): String =
    "lap=${lap.number} kind=${lap.kind.name} time_ms=${lap.timeMillis} predicted_ms=${predictedMillis ?: "none"}" +
        if (lap.gnssGap) " gnss_gap=true" else ""
