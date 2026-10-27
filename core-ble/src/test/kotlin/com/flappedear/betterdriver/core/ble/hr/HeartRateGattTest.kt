@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.sigUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateGattTest {
    @Test
    fun `scan filter matches the Heart Rate Service only`() {
        assertTrue(isHeartRateAdvertisement(listOf(sigUuid(0x180F), sigUuid(0x180D))))
        assertFalse(isHeartRateAdvertisement(listOf(sigUuid(0x1819))))
        assertFalse(isHeartRateAdvertisement(null))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", HeartRateGatt.SERVICE.toString())
        assertEquals("00002a37-0000-1000-8000-00805f9b34fb", HeartRateGatt.MEASUREMENT.toString())
        // The ids the iOS app looks for are the same service and characteristic (FBBD-333).
        assertEquals(sigUuid(HeartRateIds.SERVICE.toInt(16)), HeartRateGatt.SERVICE)
        assertEquals(sigUuid(HeartRateIds.MEASUREMENT.toInt(16)), HeartRateGatt.MEASUREMENT)
    }
}
