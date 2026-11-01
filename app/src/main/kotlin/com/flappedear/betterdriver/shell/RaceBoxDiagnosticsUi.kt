package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsSnapshot
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsText
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the diagnostics screen shows (FBBD-89). */
sealed interface DiagnosticsView {
    /**
     * A session is recording: the recorder owns the link, so nothing connects. [rows] are what the
     * recorder itself receives (UI-40 option B).
     */
    data class Recording(val rows: List<DiagRow>) : DiagnosticsView

    /** Bluetooth permission is missing. */
    data object NoPermission : DiagnosticsView

    data class Live(val header: DiagHeader, val sections: List<DiagSection>) : DiagnosticsView
}

/** One value on the diagnostics screen; the text is shared with iOS (FBBD-371). */
typealias DiagRow = com.flappedear.betterdriver.core.ble.DiagRow

/** A titled group of values (UI-39 option A: one live list), shared with iOS (FBBD-371). */
typealias DiagSection = com.flappedear.betterdriver.core.ble.DiagSection

/** The top card: a dot, a line and a detail line. */
data class DiagHeader(val state: DeviceState, val title: String, val detail: String)

/** What the RaceBox protocol does not carry, said once at the bottom of the screen. */
const val DiagNotReported = RaceBoxDiagnosticsText.NOT_REPORTED

private val ClockTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TakenAt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** How long the car must stand still for a calibration (FBBD-89): 5 s gives 125 samples at 25 Hz. */
const val CalibrationMs = RaceBoxDiagnosticsText.CALIBRATION_MS

/**
 * The diagnostics screen's text (FBBD-89), made by the shared [RaceBoxDiagnosticsText] (FBBD-371) so
 * the iPhone shows the same; here only the dot's colour and the times on the phone's clock.
 */
object RaceBoxDiagnosticsUi {
    fun header(name: String, link: LinkState, s: RaceBoxDiagnosticsSnapshot): DiagHeader =
        RaceBoxDiagnosticsText.header(name, link, s).let {
            DiagHeader(if (it.ok) DeviceState.Ready else DeviceState.Attention, it.title, it.detail)
        }

    /**
     * [streaming] enables the calibrate button; [calibrationError] is why the last attempt failed.
     * [zone] formats the receiver's time (FBBD-217) and when the calibration was taken.
     */
    fun sections(
        s: RaceBoxDiagnosticsSnapshot,
        streaming: Boolean = false,
        calibrationError: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DiagSection> = RaceBoxDiagnosticsText.sections(
        s,
        streaming,
        calibrationError,
        clock = { ClockTime.format(Instant.ofEpochMilli(it).atZone(zone)) },
        takenAt = { TakenAt.format(Instant.ofEpochMilli(it).atZone(zone)) },
    )
}

/** What the recorder receives from the RaceBox during a session (UI-40 option B). */
fun recordingRows(live: LiveSnapshot?): List<DiagRow> = RaceBoxDiagnosticsText.recordingRows(live)
