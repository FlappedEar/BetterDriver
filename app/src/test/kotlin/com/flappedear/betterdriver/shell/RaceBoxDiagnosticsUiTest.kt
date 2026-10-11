package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.core.ble.ImuCalibration
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.RaceBoxData
import com.flappedear.betterdriver.core.ble.RaceBoxDeviceInfo
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsSnapshot
import com.flappedear.betterdriver.core.ble.RaceBoxGnssConfig
import com.flappedear.betterdriver.core.ble.RaceBoxPower
import com.flappedear.betterdriver.core.ble.Vec3
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBoxDiagnosticsUiTest {
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

    private fun List<DiagSection>.value(section: String, label: String) =
        first { it.title == section }.rows.firstOrNull { it.label == label }?.value

    @Test
    fun `shows the reported values with real minus signs`() {
        val s = RaceBoxDiagnosticsUi.sections(snapshot())
        assertEquals("3D · OK", s.value("GNSS", "Fix"))
        assertEquals("0.42 m · vert 0.81 m", s.value("GNSS", "Position accuracy"))
        assertEquals("15:42:07", s.value("GNSS", "UTC time"))
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
        val s = RaceBoxDiagnosticsUi.sections(snapshot(latest = null))
        assertEquals(listOf("Data stream", "Calibration", "Receiver settings", "Device"), s.map { it.title })
        assertNull(s.value("Data stream", "Bluetooth packet size"))
    }

    @Test
    fun `header says how the link is`() {
        val ok = RaceBoxDiagnosticsUi.header("RaceBox Micro 3241", LinkState.Streaming(25), snapshot())
        assertEquals(DeviceState.Ready, ok.state)
        assertEquals("Connected · 25 Hz", ok.title)
        assertEquals("RaceBox Micro 3241 · 3D · 18 satellites", ok.detail)
        val slow = RaceBoxDiagnosticsUi.header("RaceBox Micro 3241", LinkState.Streaming(12), snapshot())
        assertEquals(DeviceState.Attention, slow.state)
        assertEquals("Connecting…", RaceBoxDiagnosticsUi.header("x", LinkState.Connecting, snapshot()).title)
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
        val s = RaceBoxDiagnosticsUi.sections(
            snapshot().copy(calibration = cal),
            streaming = true,
            zone = ZoneOffset.UTC,
        )
        assertEquals("0.00 · 0.00 · 0.00 °/s", s.value("Motion sensors · calibrated", "Rotation X · Y · Z"))
        assertEquals("roll 2.1° · pitch −0.8°", s.value("Calibration", "Tilt corrected"))
        assertEquals("11 Oct, 15:42", s.value("Calibration", "Taken"))
        val section = s.first { it.title == "Calibration" }
        assertEquals("Calibrate again", section.action)
        assertTrue(section.actionEnabled)
        val busy = RaceBoxDiagnosticsUi.sections(snapshot().copy(capturing = 40), streaming = true)
        assertFalse(busy.first { it.title == "Calibration" }.actionEnabled)
    }
}
