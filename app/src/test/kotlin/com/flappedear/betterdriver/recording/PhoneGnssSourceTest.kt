package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.PhoneFix
import com.flappedear.betterdriver.recorder.PhoneGnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.phoneFixMicros
import com.flappedear.betterdriver.recorder.phoneFixSet
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FBBD-275: the phone's GPS stored as a session's positions, marked as a phone's. */
class PhoneGnssSourceTest {
    @Test
    fun `a position is stored in the receiver channels with speed in km per h`() {
        val fix = PhoneFix(
            lat = 50.1, lon = 14.4, utcMillis = 1_790_000_000_123L, speedMs = 25.0, bearingDeg = 271.5,
            altitudeM = 310.0, accuracyM = 3.8, satellites = 14,
        )
        val set = phoneFixSet(5_000_000L, fix)
        val values = set.values.associate { it.channelId to it.value }
        assertEquals(5_000_000L, set.tMicros)
        assertEquals(1_790_000_000_123.0, values.getValue(GnssChannels.UTC_MS.id), 0.0)
        assertEquals(50.1, values.getValue(GnssChannels.LAT.id), 0.0)
        assertEquals(14.4, values.getValue(GnssChannels.LON.id), 0.0)
        assertEquals(90.0, values.getValue(GnssChannels.SPEED_KMH.id), 1e-9)
        assertEquals(271.5, values.getValue(GnssChannels.HEADING.id), 0.0)
        assertEquals(310.0, values.getValue(GnssChannels.ALT_M.id), 0.0)
        assertEquals(14.0, values.getValue(GnssChannels.SATS.id), 0.0)
        assertEquals(3.8, values.getValue(PhoneGnssChannels.ACCURACY.id), 0.0)
    }

    @Test
    fun `what Android leaves out is not stored`() {
        val set = phoneFixSet(0L, PhoneFix(lat = 50.1, lon = 14.4, utcMillis = 1L))
        assertEquals(
            setOf(GnssChannels.UTC_MS.id, GnssChannels.LAT.id, GnssChannels.LON.id),
            set.values.map { it.channelId }.toSet(),
        )
    }

    @Test
    fun `a position is placed at when it was measured, unless its stamp is implausible`() {
        val now = 60_000_000L
        val boot = 900_000_000_000L
        // Measured 300 ms before delivery.
        assertEquals(59_700_000L, phoneFixMicros(now, boot, boot - 300_000_000L))
        // A stamp in the future, or older than 2 s: the delivery time.
        assertEquals(now, phoneFixMicros(now, boot, boot + 1_000_000L))
        assertEquals(now, phoneFixMicros(now, boot, boot - 5_000_000_000L))
    }

    @Test
    fun `its channels are a phone's GPS, never a GNSS receiver's`() {
        assertEquals(GnssChannels.ALL.map { it.id }, PhoneGnssChannels.GNSS.map { it.id })
        assertTrue(PhoneGnssChannels.ALL.all { it.provenance == Provenance.MEASURED_GPS })
        val ids = PhoneGnssChannels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(PhoneImuChannels.ALL.none { it.id in ids })
    }

    @Test
    fun `the phone's GPS added as equipment starts sessions on its own source, under one name`() {
        assertEquals(GnssType.PHONE, GnssRef.Phone.type)
        assertTrue(!GnssRef.Phone.demo)
        // Renamed by Arek, it still records and logs as "Phone GPS" (the drive-log report reads Phone_GPS).
        val item = Equipment("p1", EquipmentModel.PHONE_GPS, "My phone", null)
        assertEquals(GnssRef.Phone, gnssRefOf(item))
    }
}
