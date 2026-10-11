package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.RaceBoxData
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsSnapshot
import com.flappedear.betterdriver.core.ble.Vec3
import com.flappedear.betterdriver.core.ble.RaceBoxPower
import com.flappedear.betterdriver.core.ui.format.Minus
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

/** One value on the diagnostics screen. */
data class DiagRow(val label: String, val value: String)

/**
 * A titled group of values (UI-39 option A: one live list). [note] is dim text under the rows;
 * [action] is the label of a button under them (calibration only), enabled when [actionEnabled].
 */
data class DiagSection(
    val title: String,
    val rows: List<DiagRow>,
    val note: String? = null,
    val action: String? = null,
    val actionEnabled: Boolean = false,
)

/** The top card: a dot, a line and a detail line. */
data class DiagHeader(val state: DeviceState, val title: String, val detail: String)

/** What the RaceBox protocol does not carry, said once at the bottom of the screen. */
const val DiagNotReported = "Not reported by the RaceBox: signal level per satellite, which satellite " +
    "systems are used, temperature, the backup coin cell, firmware updates."

private const val KmhPerMps = 3.6
private const val ExpectedHz = 25
private val UtcTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
private val TakenAt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** How long the car must stand still for a calibration (FBBD-89): 5 s gives 125 samples at 25 Hz. */
const val CalibrationMs = 5_000L

/**
 * Turns what the RaceBox reported into the screen's text (FBBD-89). Only reported values appear: a
 * group the device has not answered yet is left out rather than shown with dashes.
 */
object RaceBoxDiagnosticsUi {
    fun header(name: String, link: LinkState, s: RaceBoxDiagnosticsSnapshot): DiagHeader = when (link) {
        LinkState.Idle, LinkState.Connecting -> DiagHeader(DeviceState.Attention, "Connecting…", name)
        is LinkState.Reconnecting -> DiagHeader(DeviceState.Attention, "Reconnecting", "$name · ${link.reason}")
        is LinkState.Streaming -> {
            val d = s.latest
            val fix = d?.let { "${fixText(it)} · ${it.satellites} satellites" }
            val ok = link.hz >= ExpectedHz - 2 && d?.hasFix == true
            DiagHeader(
                if (ok) DeviceState.Ready else DeviceState.Attention,
                "Connected · ${link.hz} Hz",
                listOfNotNull(name, fix).joinToString(" · "),
            )
        }
    }

    /**
     * [streaming] enables the calibrate button; [calibrationError] is why the last attempt failed.
     * [zone] formats when the calibration was taken.
     */
    fun sections(
        s: RaceBoxDiagnosticsSnapshot,
        streaming: Boolean = false,
        calibrationError: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DiagSection> = listOfNotNull(
        s.latest?.let { gnss(it) },
        stream(s),
        s.power?.let { power(it) },
        s.latest?.let { sensors(it, s) },
        calibration(s, streaming, calibrationError, zone),
        settings(s),
        memory(s),
        device(s),
    )

    private fun gnss(d: RaceBoxData) = DiagSection(
        "GNSS",
        listOfNotNull(
            DiagRow("Fix", fixText(d) + if (d.fixOk) " · OK" else ""),
            DiagRow("Satellites", "${d.satellites}"),
            DiagRow("Position accuracy", "${num(d.horizontalAccM, 2)} m · vert ${num(d.verticalAccM, 2)} m"),
            DiagRow("Speed accuracy", "${num(d.speedAccMps * KmhPerMps, 2)} km/h"),
            DiagRow("Heading accuracy", "${num(d.headingAccDeg, 1)}°"),
            DiagRow("PDOP", num(d.pdop, 2)),
            d.utcMillis?.let { DiagRow("UTC time", UtcTime.format(Instant.ofEpochMilli(it))) },
        ),
    )

    private fun stream(s: RaceBoxDiagnosticsSnapshot) = DiagSection(
        "Data stream",
        listOfNotNull(
            DiagRow("Rate", "${s.rateHz} Hz"),
            DiagRow("Missed messages", "${group(s.missed)} of ${group(s.messages + s.missed)}"),
            DiagRow("Checksum errors", group(s.checksumErrors)),
            s.rssiDbm?.let { DiagRow("Signal", "${minus(it)} dBm") },
            s.mtu?.let { DiagRow("Bluetooth packet size", "$it bytes") },
        ),
    )

    private fun power(p: RaceBoxPower) = DiagSection(
        "Power",
        listOf(
            when (p) {
                is RaceBoxPower.Input -> DiagRow("Input voltage", "${num(p.volts, 1)} V")
                is RaceBoxPower.Battery ->
                    DiagRow("Battery", "${p.percent} % · " + if (p.charging) "charging" else "not charging")
            },
        ),
    )

    /** Calibrated values when a calibration is saved, as the recorder will write them. */
    private fun sensors(d: RaceBoxData, s: RaceBoxDiagnosticsSnapshot): DiagSection {
        val c = s.calibration
        val a = Vec3(d.accelXg, d.accelYg, d.accelZg).let { c?.accel(it) ?: it }
        val g = Vec3(d.gyroXdps, d.gyroYdps, d.gyroZdps).let { c?.gyro(it) ?: it }
        return DiagSection(
            if (c == null) "Motion sensors" else "Motion sensors · calibrated",
            listOf(
                DiagRow("Acceleration X · Y · Z", "${xyz(a.x, a.y, a.z, 2)} g"),
                DiagRow("Rotation X · Y · Z", "${xyz(g.x, g.y, g.z, 2)} °/s"),
            ),
        )
    }

    private fun calibration(
        s: RaceBoxDiagnosticsSnapshot,
        streaming: Boolean,
        error: String?,
        zone: ZoneId,
    ): DiagSection {
        val c = s.calibration
        val rows = if (c == null) {
            listOf(DiagRow("Status", "Not calibrated"))
        } else {
            listOf(
                DiagRow("Tilt corrected", "roll ${num(c.rollDeg, 1)}° · pitch ${num(c.pitchDeg, 1)}°"),
                DiagRow("Gyro offset removed", "${xyz(c.gyroBias.x, c.gyroBias.y, c.gyroBias.z, 2)} °/s"),
                DiagRow("Taken", TakenAt.format(Instant.ofEpochMilli(c.atMillis).atZone(zone))),
            )
        }
        val note = when {
            s.capturing != null -> "Keep the car still… ${s.capturing} samples"
            error != null -> error
            else -> "Park on level ground, engine off if possible, and keep still for 5 s. " +
                "Applied to acceleration and rotation in every new session. The RaceBox's X axis must point forward."
        }
        return DiagSection(
            "Calibration",
            rows,
            note = note,
            action = if (c == null) "Calibrate" else "Calibrate again",
            actionEnabled = streaming && s.capturing == null,
        )
    }

    private fun settings(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
        val c = s.gnssConfig ?: return null
        return DiagSection(
            "Receiver settings",
            listOf(
                DiagRow("Platform", c.platformName),
                DiagRow("Speed", if (c.speed3d) "3D" else "Ground (2D)"),
                DiagRow("Fix needs accuracy of", "${c.minHorizontalAccuracyM} m"),
            ),
        )
    }

    private fun memory(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
        val m = s.memory ?: return null
        val lock = when {
            !m.securityEnabled -> "Off"
            m.unlocked -> "On, unlocked"
            else -> "On, locked"
        }
        return DiagSection(
            "Memory",
            listOf(
                DiagRow("Standalone recording", if (m.recording) "On" else "Off"),
                DiagRow("Used", "${m.levelPercent} % · ${group(m.storedMessages)} of ${group(m.capacityMessages)}"),
                DiagRow("Memory lock", lock),
            ),
        )
    }

    private fun device(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
        val i = s.info
        val rows = listOfNotNull(
            i.model?.let { DiagRow("Model", it) },
            i.serial?.let { DiagRow("Serial", it) },
            i.firmware?.let { DiagRow("Firmware", it) },
            i.hardware?.let { DiagRow("Hardware", it) },
            i.manufacturer?.let { DiagRow("Manufacturer", it) },
        )
        return if (rows.isEmpty()) null else DiagSection("Device", rows)
    }

}

/** Fix status values of the data message (p. 5). */
private const val MicrosPerSecond = 1_000_000.0

/**
 * What the recorder receives from the RaceBox during a session (UI-40 option B). The recorder does
 * not pass the fix type to the screen, so the age of the last fix stands in for it.
 */
fun recordingRows(live: LiveSnapshot?): List<DiagRow> = live?.let {
    listOf(
        DiagRow("Rate", "${it.fixRateHz} Hz"),
        DiagRow("Satellites", it.sats?.toString() ?: "None yet"),
        DiagRow(
            "Last fix",
            it.lastFixAgeMicros?.let { age -> "${num(age / MicrosPerSecond, 1)} s ago" } ?: "None yet",
        ),
    )
}.orEmpty()

private const val FixNone = 0
private const val Fix2D = 2
private const val Fix3D = 3

private fun fixText(d: RaceBoxData): String = when (d.fixType) {
    FixNone -> "No fix"
    Fix2D -> "2D"
    Fix3D -> "3D"
    else -> "Type ${d.fixType}"
}

private fun num(v: Double, decimals: Int): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", v)
    // No "−0.00": a value that rounds to zero has no sign.
    return if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else minus(text)
}

private fun xyz(x: Double, y: Double, z: Double, decimals: Int) =
    listOf(x, y, z).joinToString(" · ") { num(it, decimals) }

/** A real minus sign, as elsewhere in the app's numbers. */
private fun minus(v: Any): String = v.toString().replace("-", Minus)

/** 15004 → "15 004". */
private fun group(n: Long): String = String.format(Locale.ROOT, "%,d", n).replace(',', ' ')
