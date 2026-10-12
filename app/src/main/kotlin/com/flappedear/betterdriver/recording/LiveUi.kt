package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.feature.track.TrackAlert
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.feature.track.TrackTile
import com.flappedear.betterdriver.feature.track.withTiming
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.timing.live.LiveTimingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val MicrosPerSecond = 1_000_000L
private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 3_600L
private const val BytesPerMb = 1_000_000L
private const val NoValue = "–"

/**
 * Track screen values from the recorder. Laps, delta and sectors come from live timing, which runs
 * in the recorder process once the session is at a saved track with lines (FBBD-12); until then
 * those slots show their empty state. The coach line is the coach's last cue (FBBD-98). [tiles] are the
 * tiles set in Settings (UI-38 A), already limited to what the session's receiver can fill.
 */
fun liveTrackState(state: RecorderState, tiles: List<TrackChannel>): TrackScreenState {
    val live = state.live
    val gnss = if (live == null) {
        StatusItem("GNSS", NoValue, ok = false)
    } else {
        when (live.gnss) {
            GnssState.Waiting -> StatusItem("GNSS", "Waiting", ok = false)
            GnssState.Lost -> StatusItem("GNSS", "Lost", ok = false)
            GnssState.Ok -> StatusItem("GNSS", "${live.fixRateHz} Hz · ${live.sats ?: NoValue} sats")
        }
    }
    val timing = state.timing
    // A saved track without timing is amber. A circuit from the built-in list is only where the car is:
    // a road drive can start within its 2 km, so it is shown as neutral information, not a problem.
    val place = state.place?.let {
        if (state.placeSaved) StatusItem("Track", it, ok = timing != null) else StatusItem("Near", it)
    }
    // Phone video, when a camera reports (UI-07): green while at full quality, amber once stepped down or stopped.
    val camera = state.camera?.let { StatusItem("Camera", it.text, it.ok) }
    val screen = TrackScreenState(
        status = listOfNotNull(place, gnss, camera),
        recordingTime = live?.let { formatClock(it.elapsedMicros) },
        alert = trackAlert(state),
        deltaSeconds = null,
        predictedMillis = null,
        lastMillis = null,
        bestMillis = null,
        sectors = listOf(SectorState("S1", null, current = true), SectorState("S2", null), SectorState("S3", null)),
        tiles = tiles.map { ch -> TrackTile(tileLabel(ch, timing), tileValue(ch, live, timing) ?: NoValue, ch.unit) },
        coachCorner = state.coach?.corner,
        coachCue = state.coach?.text,
    )
    return if (timing == null) screen else screen.withTiming(timing)
}

/** At a point-to-point track (FBBD-155) the lap tile counts runs. */
private fun tileLabel(ch: TrackChannel, timing: LiveTimingState?): String =
    if (ch == TrackChannel.LAP && timing?.pointToPoint == true) "Run" else ch.label

private fun tileValue(ch: TrackChannel, live: LiveSnapshot?, timing: LiveTimingState?): String? = when (ch) {
    TrackChannel.SPEED -> live?.speedKmh?.roundToInt()?.toString()
    TrackChannel.G -> live?.gCombined?.let { String.format(Locale.ROOT, "%.2f", it) }
    TrackChannel.LAP -> timing?.lapNumber?.takeIf { it > 0 }?.toString()
}

/** The one interruption on the track screen (UI-08): only when recording is not as it should be. */
private fun trackAlert(state: RecorderState): TrackAlert? {
    val live = state.live ?: return if (state.starting) {
        TrackAlert("Starting recording", "", AlertLevel.Warning)
    } else {
        TrackAlert("Not recording", state.error ?: "Hold anywhere to go back", AlertLevel.Critical)
    }
    val storageLow = live.storageLowBytes
    return when {
        live.gnss == GnssState.Lost -> TrackAlert("GNSS signal lost", "Recording continues", AlertLevel.Critical)
        storageLow != null -> TrackAlert("Storage low", "${storageLow / BytesPerMb} MB free", AlertLevel.Warning)
        else -> null
    }
}

/** Recording time as m:ss, or h:mm:ss from one hour. */
fun formatClock(micros: Long): String {
    val s = micros.coerceAtLeast(0) / MicrosPerSecond
    val h = s / SecondsPerHour
    val m = (s % SecondsPerHour) / SecondsPerMinute
    val sec = s % SecondsPerMinute
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, sec)
    }
}

/** Session length in whole minutes, for example "18 min"; "<1 min" below one minute. */
fun formatDuration(micros: Long): String {
    val minutes = (micros.coerceAtLeast(0) / MicrosPerSecond / SecondsPerMinute)
    return if (minutes == 0L) "<1 min" else "$minutes min"
}

private val TitleFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

/** For example "Sat 11 Oct, 14:02", in the phone's time zone. */
fun sessionTitle(s: SessionSummary, zone: ZoneId = ZoneId.systemDefault()): String =
    TitleFormat.format(Instant.ofEpochMilli(s.info.startUtcMillis).atZone(zone))

/** Track, car and length, the ones that are known. */
fun sessionDetail(s: SessionSummary): String =
    listOfNotNull(
        s.info.track.ifBlank { null },
        s.info.car.ifBlank { null },
        s.durationMicros?.let(::formatDuration),
    ).joinToString(" · ")

/** The home screen's last-session line. */
fun lastSessionLine(sessions: List<SessionSummary>): String? {
    val s = sessions.firstOrNull { it.state != SessionState.RECORDING } ?: return null
    val recovered = if (s.state == SessionState.RECOVERED) " · recovered" else ""
    return listOfNotNull(sessionTitle(s), s.durationMicros?.let(::formatDuration)).joinToString(" · ") + recovered
}
