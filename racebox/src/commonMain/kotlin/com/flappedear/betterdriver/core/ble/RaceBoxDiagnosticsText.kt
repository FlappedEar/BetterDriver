package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.format.Minus
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.live.LiveSnapshot

/** One value on the RaceBox diagnostics screen. */
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

/** The top card's text: [ok] is a green dot, otherwise amber; a line and a detail line. */
data class DiagHeaderText(val ok: Boolean, val title: String, val detail: String)

/**
 * Turns what the RaceBox reported into the diagnostics screen's text (FBBD-89), the same on Android
 * and iOS (FBBD-371). Only reported values appear: a group the device has not answered yet is left
 * out rather than shown with dashes. Times come formatted by the platform on the phone's clock.
 */
object RaceBoxDiagnosticsText {
    /** The screen's title: the RaceBox's live values, not Settings › Diagnostics (the app's log). */
    const val TITLE = "RaceBox diagnostics"

    /** What the RaceBox protocol does not carry, said once at the bottom of the screen. */
    const val NOT_REPORTED = "Not reported by the RaceBox: signal level per satellite, which satellite " +
        "systems are used, temperature, the backup coin cell, firmware updates."

    /** How long the car must stand still for a calibration (FBBD-89): 5 s gives 125 samples at 25 Hz. */
    const val CALIBRATION_MS = 5_000L

    fun header(name: String, link: LinkState, s: RaceBoxDiagnosticsSnapshot): DiagHeaderText = when (link) {
        LinkState.Idle, LinkState.Connecting -> DiagHeaderText(false, "Connecting…", name)
        is LinkState.Reconnecting -> DiagHeaderText(false, "Reconnecting", "$name · ${link.reason}")
        is LinkState.Streaming -> {
            val d = s.latest
            val fix = d?.let { "${fixText(it)} · ${it.satellites} satellites" }
            DiagHeaderText(
                link.hz >= ExpectedHz - 2 && d?.hasFix == true,
                "Connected · ${link.hz} Hz",
                listOfNotNull(name, fix).joinToString(" · "),
            )
        }
    }

    /**
     * [streaming] enables the calibrate button; [calibrationError] is why the last attempt failed.
     * [clock] formats the receiver's UTC time as HH:mm:ss on the phone's clock (FBBD-217); [takenAt]
     * formats when the calibration was taken, as "11 Oct, 15:42".
     */
    fun sections(
        s: RaceBoxDiagnosticsSnapshot,
        streaming: Boolean,
        calibrationError: String?,
        clock: (Long) -> String,
        takenAt: (Long) -> String,
    ): List<DiagSection> = listOfNotNull(
        s.latest?.let { DiagSections.gnss(it, clock) },
        DiagSections.stream(s),
        s.power?.let { DiagSections.power(it) },
        s.latest?.let { DiagSections.sensors(it, s) },
        DiagSections.calibration(s, streaming, calibrationError, takenAt),
        DiagSections.settings(s),
        DiagSections.memory(s),
        DiagSections.device(s),
    )

    /**
     * The read-only queries this device's firmware answers (FBBD-89): the receiver settings from
     * firmware 3.3, and the memory status from every model but the plain Mini, which has no memory
     * (p. 10). Asked once the Device Information strings are read.
     */
    fun queries(info: RaceBoxDeviceInfo): List<ByteArray> = buildList {
        if (info.supportsGnssConfig) add(RaceBoxMessages.gnssConfigQuery())
        if (info.model != null && info.model != "RaceBox Mini") add(RaceBoxMessages.recordingStatusQuery())
    }

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

}

/** The screen's groups, one function each (FBBD-89). */
private object DiagSections {
    fun gnss(d: RaceBoxData, clock: (Long) -> String) = DiagSection(
        "GNSS",
        listOfNotNull(
            DiagRow("Fix", fixText(d) + if (d.fixOk) " · OK" else ""),
            DiagRow("Satellites", "${d.satellites}"),
            DiagRow("Position accuracy", "${num(d.horizontalAccM, 2)} m · vert ${num(d.verticalAccM, 2)} m"),
            DiagRow("Speed accuracy", "${num(d.speedAccMps * KmhPerMps, 2)} km/h"),
            DiagRow("Heading accuracy", "${num(d.headingAccDeg, 1)}°"),
            DiagRow("PDOP", num(d.pdop, 2)),
            d.utcMillis?.let { DiagRow("GPS time", clock(it)) },
        ),
    )

    fun stream(s: RaceBoxDiagnosticsSnapshot) = DiagSection(
        "Data stream",
        listOfNotNull(
            DiagRow("Rate", "${s.rateHz} Hz"),
            DiagRow("Missed messages", "${diagGroup(s.missed)} of ${diagGroup(s.messages + s.missed)}"),
            DiagRow("Checksum errors", diagGroup(s.checksumErrors)),
            s.rssiDbm?.let { DiagRow("Signal", "${minus(it)} dBm") },
            s.mtu?.let { DiagRow("Bluetooth packet size", "$it bytes") },
        ),
    )

    fun power(p: RaceBoxPower) = DiagSection(
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
    fun sensors(d: RaceBoxData, s: RaceBoxDiagnosticsSnapshot): DiagSection {
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

    fun calibration(
        s: RaceBoxDiagnosticsSnapshot,
        streaming: Boolean,
        error: String?,
        takenAt: (Long) -> String,
    ): DiagSection {
        val c = s.calibration
        val rows = if (c == null) {
            listOf(DiagRow("Status", "Not calibrated"))
        } else {
            listOf(
                DiagRow("Tilt corrected", "roll ${num(c.rollDeg, 1)}° · pitch ${num(c.pitchDeg, 1)}°"),
                DiagRow("Gyro offset removed", "${xyz(c.gyroBias.x, c.gyroBias.y, c.gyroBias.z, 2)} °/s"),
                DiagRow("Taken", takenAt(c.atMillis)),
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

    fun settings(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
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

    fun memory(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
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
                DiagRow(
                    "Used",
                    "${m.levelPercent} % · ${diagGroup(m.storedMessages)} of ${diagGroup(m.capacityMessages)}",
                ),
                DiagRow("Memory lock", lock),
            ),
        )
    }

    fun device(s: RaceBoxDiagnosticsSnapshot): DiagSection? {
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

private fun fixText(d: RaceBoxData): String = when (d.fixType) {
        FixNone -> "No fix"
        Fix2D -> "2D"
        Fix3D -> "3D"
        else -> "Type ${d.fixType}"
    }

private fun num(v: Double, decimals: Int): String {
        val text = fixed(v, decimals)
        // No "−0.00": a value that rounds to zero has no sign.
        return if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else minus(text)
    }

private fun xyz(x: Double, y: Double, z: Double, decimals: Int) =
        listOf(x, y, z).joinToString(" · ") { num(it, decimals) }

    /** A real minus sign, as elsewhere in the app's numbers. */
private fun minus(v: Any): String = v.toString().replace("-", Minus)

/** 15004 → "15 004". */
internal fun diagGroup(n: Long): String {
    val digits = if (n < 0) (-n).toString() else n.toString()
    val grouped = digits.reversed().chunked(GroupDigits).joinToString(" ").reversed()
    return if (n < 0) "-$grouped" else grouped
}

private const val KmhPerMps = 3.6
private const val ExpectedHz = 25
private const val MicrosPerSecond = 1_000_000.0
private const val GroupDigits = 3

// Fix status values of the data message (p. 5).
private const val FixNone = 0
private const val Fix2D = 2
private const val Fix3D = 3

/**
 * Device Information Service strings (p. 1) by their 16-bit characteristic id, for the iOS link,
 * which reads them by CoreBluetooth's short ids ("2A24"); Android maps its own UUIDs.
 */
object RaceBoxDeviceInfoIds {
    const val SERVICE = "180A"
    const val MODEL = "2A24"
    const val SERIAL = "2A25"
    const val FIRMWARE = "2A26"
    const val HARDWARE = "2A27"
    const val MANUFACTURER = "2A29"

    /** Read in this order; the firmware comes before the queries that depend on it. */
    val ALL = listOf(MODEL, SERIAL, FIRMWARE, HARDWARE, MANUFACTURER)

    /** [info] with the string read from characteristic [id]; unchanged for an id not listed. */
    fun apply(info: RaceBoxDeviceInfo, id: String, value: String): RaceBoxDeviceInfo = when (id.uppercase()) {
        MODEL -> info.copy(model = value)
        SERIAL -> info.copy(serial = value)
        FIRMWARE -> info.copy(firmware = value)
        HARDWARE -> info.copy(hardware = value)
        MANUFACTURER -> info.copy(manufacturer = value)
        else -> info
    }
}
