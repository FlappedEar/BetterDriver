package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RaceBox diagnostics screen's text, shared by Android and iOS (FBBD-371): run on the JVM and on
 * the iOS simulator, so both phones show the same numbers (the Android app's RaceBoxDiagnosticsUiTest
 * checks the same values through its adapter).
 */
class RaceBoxDiagnosticsTextTest {
    private val data = RaceBoxData(
        iTowMs = 0, utcMillis = 1_791_733_327_000L, fixType = 3, fixOk = true, headingValid = false,
        coordinatesInvalid = false, satellites = 18, latDeg = 52.4, lonDeg = 16.8, wgsAltM = 100.0,
        mslAltM = 60.0, horizontalAccM = 0.424, verticalAccM = 0.81, speedMps = 0.0, headingDeg = 0.0,
        speedAccMps = 0.05, headingAccDeg = 0.9, pdop = 1.21, batteryStatus = 121,
        accelXg = 0.012, accelYg = -0.021, accelZg = 0.99, gyroXdps = -0.001, gyroYdps = 0.0, gyroZdps = 0.03,
    )

    private fun snapshot(latest: RaceBoxData? = data) = RaceBoxDiagnosticsSnapshot(
        info = RaceBoxDeviceInfo(model = "RaceBox Micro", firmware = "3.4"),
        latest = latest,
        power = latest?.let { RaceBoxPower.Input(12.1) },
        rateHz = 25,
        messages = 15_001,
        missed = 3,
        checksumErrors = 0,
        rssiDbm = -58,
        mtu = null,
        gnssConfig = RaceBoxGnssConfig(4, false, 3),
        memory = null,
        nackSeen = false,
    )

    private fun sections(s: RaceBoxDiagnosticsSnapshot, streaming: Boolean = false) =
        RaceBoxDiagnosticsText.sections(s, streaming, null, clock = { "clock $it" }, takenAt = { "taken $it" })

    private fun List<DiagSection>.value(section: String, label: String) =
        first { it.title == section }.rows.firstOrNull { it.label == label }?.value

    @Test
    fun `shows the reported values with real minus signs`() {
        val s = sections(snapshot())
        assertEquals("3D · OK", s.value("GNSS", "Fix"))
        assertEquals("0.42 m · vert 0.81 m", s.value("GNSS", "Position accuracy"))
        assertEquals("0.18 km/h", s.value("GNSS", "Speed accuracy"))
        assertEquals("1.21", s.value("GNSS", "PDOP"))
        assertEquals("clock 1791733327000", s.value("GNSS", "GPS time"))
        assertEquals("3 of 15 004", s.value("Data stream", "Missed messages"))
        assertEquals("−58 dBm", s.value("Data stream", "Signal"))
        assertEquals("12.1 V", s.value("Power", "Input voltage"))
        assertEquals("0.01 · −0.02 · 0.99 g", s.value("Motion sensors", "Acceleration X · Y · Z"))
        assertEquals("0.00 · 0.00 · 0.03 °/s", s.value("Motion sensors", "Rotation X · Y · Z"))
        assertEquals("Automotive", s.value("Receiver settings", "Platform"))
        assertEquals("3.4", s.value("Device", "Firmware"))
    }

    @Test
    fun `leaves out what the device has not reported`() {
        val s = sections(snapshot(latest = null))
        assertEquals(listOf("Data stream", "Calibration", "Receiver settings", "Device"), s.map { it.title })
        assertNull(s.value("Data stream", "Bluetooth packet size"))
    }

    @Test
    fun `header says how the link is`() {
        val ok = RaceBoxDiagnosticsText.header("RaceBox Micro 3241", LinkState.Streaming(25), snapshot())
        assertTrue(ok.ok)
        assertEquals("Connected · 25 Hz", ok.title)
        assertEquals("RaceBox Micro 3241 · 3D · 18 satellites", ok.detail)
        assertFalse(RaceBoxDiagnosticsText.header("x", LinkState.Streaming(12), snapshot()).ok)
        assertEquals("Connecting…", RaceBoxDiagnosticsText.header("x", LinkState.Connecting, snapshot()).title)
        val lost = RaceBoxDiagnosticsText.header("x", LinkState.Reconnecting(1, 500, "timeout"), snapshot())
        assertEquals("x · timeout", lost.detail)
    }

    @Test
    fun `calibration shows what was corrected and applies it to the sensor values`() {
        val cal = ImuCalibration(
            gyroBias = Vec3(0.0, 0.0, 0.03),
            level = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            rollDeg = 2.14,
            pitchDeg = -0.8,
            atMillis = 1_791_733_327_000L,
        )
        val s = sections(snapshot().copy(calibration = cal), streaming = true)
        assertEquals("0.00 · 0.00 · 0.00 °/s", s.value("Motion sensors · calibrated", "Rotation X · Y · Z"))
        assertEquals("roll 2.1° · pitch −0.8°", s.value("Calibration", "Tilt corrected"))
        assertEquals("taken 1791733327000", s.value("Calibration", "Taken"))
        val section = s.first { it.title == "Calibration" }
        assertEquals("Calibrate again", section.action)
        assertTrue(section.actionEnabled)
        val busy = sections(snapshot().copy(capturing = 40), streaming = true).first { it.title == "Calibration" }
        assertFalse(busy.actionEnabled)
        assertEquals("Keep the car still… 40 samples", busy.note)
    }

    @Test
    fun `memory and battery read as the device reports them`() {
        val s = sections(
            snapshot().copy(
                power = RaceBoxPower.Battery(89, false),
                memory = RaceBoxMemoryStatus(false, 34, true, false, 67_173, 196_608),
            ),
        )
        assertEquals("89 % · not charging", s.value("Power", "Battery"))
        assertEquals("34 % · 67 173 of 196 608", s.value("Memory", "Used"))
        assertEquals("On, locked", s.value("Memory", "Memory lock"))
    }

    @Test
    fun `asks only what the firmware answers`() {
        assertTrue(RaceBoxDiagnosticsText.queries(RaceBoxDeviceInfo()).isEmpty())
        val plainMini = RaceBoxDiagnosticsText.queries(RaceBoxDeviceInfo(model = "RaceBox Mini", firmware = "3.3"))
        assertEquals(1, plainMini.size)
        assertContentEquals(RaceBoxMessages.gnssConfigQuery(), plainMini[0])
        val micro = RaceBoxDiagnosticsText.queries(RaceBoxDeviceInfo(model = "RaceBox Micro", firmware = "2.6"))
        assertEquals(1, micro.size)
        assertContentEquals(RaceBoxMessages.recordingStatusQuery(), micro[0])
    }

    @Test
    fun `device information strings by their short ids`() {
        var info = RaceBoxDeviceInfo()
        info = RaceBoxDeviceInfoIds.apply(info, "2a24", "RaceBox Micro")
        info = RaceBoxDeviceInfoIds.apply(info, "2A26", "3.4")
        info = RaceBoxDeviceInfoIds.apply(info, "2A00", "ignored")
        assertEquals(RaceBoxDeviceInfo(model = "RaceBox Micro", firmware = "3.4"), info)
    }

    @Test
    fun `groups digits with spaces`() {
        assertEquals("0", diagGroup(0))
        assertEquals("999", diagGroup(999))
        assertEquals("1 000", diagGroup(1_000))
        assertEquals("1 234 567", diagGroup(1_234_567))
        assertEquals("-12 345", diagGroup(-12_345))
    }

    @Test
    fun `during a session shows what the recorder receives`() {
        val live = LiveSnapshot(
            sessionId = "s", startUtcMillis = 0, elapsedMicros = 0, fixes = 100, fixRateHz = 25,
            lastFixAgeMicros = 40_000, sats = 18, speedKmh = 0.0, storageLowBytes = null,
            stoppedReason = null, droppedSamples = 0,
        )
        assertEquals(
            listOf(DiagRow("Rate", "25 Hz"), DiagRow("Satellites", "18"), DiagRow("Last fix", "0.0 s ago")),
            RaceBoxDiagnosticsText.recordingRows(live),
        )
        assertEquals("None yet", RaceBoxDiagnosticsText.recordingRows(live.copy(sats = null))[1].value)
        assertTrue(RaceBoxDiagnosticsText.recordingRows(null).isEmpty())
    }
}
