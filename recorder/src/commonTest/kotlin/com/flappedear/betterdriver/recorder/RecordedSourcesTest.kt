package com.flappedear.betterdriver.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FBBD-436: an iPhone session listed only its receiver ("Device Phone GPS" in the VBO), where Android
 * lists phone_gnss, phone_imu, obd, heart_rate and tpms.
 */
class RecordedSourcesTest {
    @Test
    fun `an iPhone session with phone GPS lists every source it starts, by Android's names`() {
        val devices = RecordedSources.list(
            "Phone GPS", RecordedReceiver.PHONE, phoneImu = true, obd = true, heartRate = true, tyres = true,
        )
        assertEquals(listOf("Phone GPS", "phone_gnss", "phone_imu", "obd", "heart_rate", "tpms"), devices)
        // Stored without provenance, its GNSS channels read as a phone's GPS, as Android's do.
        assertEquals(Provenance.MEASURED_GPS, Provenance.forSession(GnssChannels.LAT, devices, null))
    }

    @Test
    fun `a RaceBox keeps its own name first, for the log and the Micro check`() {
        val devices = RecordedSources.list(
            "RaceBox Micro 3242703553", RecordedReceiver.RACEBOX,
            phoneImu = true, obd = false, heartRate = false, tyres = true,
        )
        assertEquals(listOf("RaceBox Micro 3242703553", "racebox", "phone_imu", "tpms"), devices)
    }

    @Test
    fun `the Demo track is made up, and a listed receiver adds no kind`() {
        val demo = RecordedSources.list(
            "Demo track", RecordedReceiver.MADE_UP, phoneImu = false, obd = false, heartRate = false, tyres = false,
        )
        assertEquals(listOf("Demo track", Provenance.DemoDevice), demo)
        assertEquals(Provenance.SIMULATED, Provenance.forSession(GnssChannels.LAT, demo, null))
        val listed = RecordedSources.list(
            "Wi-Fi GPS", RecordedReceiver.OTHER, phoneImu = true, obd = false, heartRate = true, tyres = false,
        )
        assertEquals(listOf("Wi-Fi GPS", "phone_imu", "heart_rate"), listed)
    }
}
