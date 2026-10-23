package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.StatusIcon
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.feature.track.GRadar
import com.flappedear.betterdriver.feature.track.RadarPoint
import com.flappedear.betterdriver.feature.track.TrackAlert
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.feature.track.TileChoiceGroup
import com.flappedear.betterdriver.feature.track.withTiming
import com.flappedear.betterdriver.obd.ObdLinkState
import com.flappedear.betterdriver.recorder.live.GPoint
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.setup.TrackPage
import com.flappedear.betterdriver.setup.TrackScreens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MicrosPerSecond = 1_000_000L
private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 3_600L
private const val BytesPerMb = 1_000_000L
private const val NoValue = "–"

/**
 * Track screen values from the recorder. Laps and delta come from live timing, which runs
 * in the recorder process once the session is at a saved track with lines (FBBD-12); until then
 * those slots show their empty state. The coach line is the coach's last cue (FBBD-98). [screens]
 * are the session's tiles (FBBD-262); [receiverHasG] is whether its receiver gives G.
 */
/**
 * The rate on the GNSS status: the receiver's, from the spacing of its recent fixes. Fixes counted in
 * the last second read low for the first second of a session or of a fix (8 Hz on Arek's 2026-10-14
 * drive while the RaceBox sent 25), which made him swap a working receiver.
 */
internal fun shownRateHz(live: LiveSnapshot): Int = if (live.receiverRateHz > 0) live.receiverRateHz else live.fixRateHz

fun liveTrackState(state: RecorderState, screens: TrackScreens, receiverHasG: Boolean): TrackScreenState {
    val live = state.live
    val gnss = if (live == null) {
        StatusItem("GNSS", NoValue, ok = false)
    } else {
        when (live.gnss) {
            GnssState.Waiting -> StatusItem("GNSS", "Waiting", ok = false)
            GnssState.Lost -> StatusItem("GNSS", "Lost", ok = false)
            GnssState.Ok -> StatusItem("GNSS", "${shownRateHz(live)} Hz · ${live.sats ?: NoValue} sats")
        }
    }
    val timing = state.timing
    val sources = TileSources(live, timing, receiverHasG)
    // The quiet strip (UI-141 B) names the track only when something is wrong there: a saved track
    // without timing, in amber. A circuit from the built-in list is only where the car is, not a problem.
    val place = state.place?.takeIf { state.placeSaved && timing == null }?.let { StatusItem("Track", it, ok = false) }
    // Phone video, when a camera reports (UI-07): green while at full quality, amber once stepped down or stopped.
    val camera = state.camera?.let { StatusItem("Camera", it.text, it.ok) }
    // OBD and heart rate (FBBD-268): an icon and a value, shown only while their data arrives (UI-110 B,
    // UI-111 B, UI-112 A). The heart rate is the strap's last value, never older than 5 s.
    val obd = state.obd?.takeIf { it.state == ObdLinkState.Connected }
        ?.let { StatusItem("OBD", "Connected", icon = StatusIcon.Car) }
    val heart = state.heartRate?.bpm?.let { StatusItem("Heart rate", "$it", icon = StatusIcon.Heart, keepValue = true) }
    val screen = TrackScreenState(
        status = listOfNotNull(place, gnss, camera, obd, heart),
        recordingTime = live?.let { formatClock(it.elapsedMicros) },
        alert = trackAlert(state),
        deltaSeconds = null,
        lapNumber = null,
        lapMillis = null,
        lastMillis = null,
        bestMillis = null,
        tiles = screens.tiles(TrackPage.MAIN).map { trackTile(it, sources) },
        coachCorner = state.coach?.corner,
        coachCue = state.coach?.text,
        coachPraise = state.coach?.praise == true,
        coachDim = state.coach?.dim == true,
        secondTiles = screens.tiles(TrackPage.SECOND).map { trackTile(it, sources) },
        editable = stopped(live),
        // The radar shows with a receiver that gives G, as the G tile does (UI-126 B).
        radarOn = receiverHasG,
        radar = live?.let(::radarOf),
    )
    return if (timing == null) screen else screen.withTiming(timing)
}

/** The G radar's dot and trail (FBBD-318), or null before the first G. */
internal fun radarOf(live: LiveSnapshot): GRadar? {
    val now = live.g ?: return null
    val point = { p: GPoint -> RadarPoint(p.forward.toFloat(), p.left.toFloat()) }
    return GRadar(point(now), live.gTrail.map(point))
}

/**
 * The car is not moving, so a tile can be changed (UI-123 B): no recording, no fix to tell, or
 * under [EditMaxKmh].
 */
internal fun stopped(live: LiveSnapshot?): Boolean =
    live == null || live.gnss != GnssState.Ok || (live.speedKmh ?: 0.0) < EditMaxKmh

/** Walking pace: a car in the pit lane queue still counts as stopped. */
private const val EditMaxKmh = 5.0

/** A tile's list on the track screen (UI-124 A), its values from [state]. */
fun liveTileChoices(state: RecorderState, receiverHasG: Boolean, receiver: String?): List<TileChoiceGroup> =
    tileChoices(TileSources(state.live, state.timing, receiverHasG), receiver)

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

