package com.flappedear.betterdriver.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone-GPS stamping both apps use (FBBD-275, FBBD-332). */
class PhoneGnssTest {
    @Test
    fun aFixIsStampedWhenItWasMeasured() {
        assertEquals(59_700_000L, phoneFixMicrosByAge(60_000_000L, 300_000L))
    }

    @Test
    fun anImplausibleAgeFallsBackToDeliveryTime() {
        assertEquals(60_000_000L, phoneFixMicrosByAge(60_000_000L, -1L))
        assertEquals(60_000_000L, phoneFixMicrosByAge(60_000_000L, 5_000_000L))
    }

    @Test
    fun aSetHoldsOnlyWhatThePhoneGave() {
        val set = phoneFixSet(0L, PhoneFix(lat = 50.1, lon = 14.4, utcMillis = 1L, speedMs = 10.0))
        val ids = set.values.map { it.channelId }
        val expected = listOf(GnssChannels.UTC_MS, GnssChannels.LAT, GnssChannels.LON, GnssChannels.SPEED_KMH)
        assertEquals(expected.map { it.id }, ids)
        assertEquals(36.0, set.values.last().value, 1e-9)
    }
}
