package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which live page each equipment item's page links to (FBBD-440), the same on Android and the iPhone. */
class EquipmentLiveTest {
    @Test
    fun everyDeviceThatConnectsHasLiveData() {
        assertEquals(LivePage.DIAGNOSTICS, EquipmentLive.page(EquipmentModel.RACEBOX))
        assertEquals("Diagnostics", EquipmentLive.rowLabel(EquipmentModel.RACEBOX))
        val sources = listOf(
            EquipmentModel.NMEA_GNSS,
            EquipmentModel.PHONE_GPS,
            EquipmentModel.HEART_RATE,
            EquipmentModel.TPMS_BRIDGE,
        )
        for (m in sources) {
            assertEquals(LivePage.SOURCE, EquipmentLive.page(m), m.name)
            assertEquals("Live data", EquipmentLive.rowLabel(m))
        }
        assertEquals(LivePage.CAR_DATA, EquipmentLive.page(EquipmentModel.OBD_BLUETOOTH))
        assertEquals(LivePage.CAR_DATA, EquipmentLive.page(EquipmentModel.OBD_WIFI))
        assertEquals(LivePage.CAMERA_TEST, EquipmentLive.page(EquipmentModel.PHONE_CAMERA))
        assertEquals(LivePage.GOPRO, EquipmentLive.page(EquipmentModel.GOPRO))
    }

    @Test
    fun camerasRunByHandHaveNone() {
        assertNull(EquipmentLive.rowLabel(EquipmentModel.GOPRO_FILES))
        assertNull(EquipmentLive.rowLabel(EquipmentModel.CAMERA_FILES))
    }
}
