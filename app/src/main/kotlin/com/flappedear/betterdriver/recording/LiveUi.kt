package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.feature.track.TrackAlert
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.feature.track.TrackTile
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
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
 * Track screen values from the recorder. Laps, delta, sectors and coach cues come from the lap
 * timer and coach, which are not wired yet, so those slots show their empty state.
 */
fun liveTrackState(state: RecorderState): TrackScreenState {
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
    return TrackScreenState(
        status = listOf(gnss),
        recordingTime = live?.let { formatClock(it.elapsedMicros) },
        alert = trackAlert(state),
        deltaSeconds = null,
        predictedMillis = null,
        lastMillis = null,
        bestMillis = null,
        sectors = listOf(SectorState("S1", null, current = true), SectorState("S2", null), SectorState("S3", null)),
        tiles = listOf(
            TrackTile("Speed", live?.speedKmh?.roundToInt()?.toString() ?: NoValue, "km/h"),
            TrackTile("G", NoValue, "g"),
            TrackTile("Lap", NoValue, ""),
        ),
        coachCorner = null,
        coachCue = null,
    )
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
