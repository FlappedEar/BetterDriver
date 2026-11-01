package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Only what Settings › Equipment lists records, on Android and iOS alike (FBBD-349, audit BD-10). */
class SessionEquipmentTest {
    private var next = 0
    private fun id() = "id${next++}"

    private val listed = Setup.fresh(::id)
        .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "6F1A2B3C-0000-4000-8000-000000000002", ::id)
        .addEquipment(EquipmentModel.OBD_WIFI, "Wi-Fi OBD-II adapter", WifiObdAddress, ::id)
        .addEquipment(EquipmentModel.HEART_RATE, "Polar H10 1234", "6F1A2B3C-0000-4000-8000-000000000001", ::id)

    @Test
    fun theListedAdaptersAndStrapAreUsed() {
        val active = listed.active()
        assertEquals(listOf("vLinker MC+"), active.obdBluetooth.map { it.name })
        assertEquals(listOf(TcpAddress("192.168.0.10", 35000)), active.obdWifi.map { it.at })
        assertEquals("Polar H10 1234", active.heartRateStrap?.name)
    }

    @Test
    fun anAdapterOrStrapRemovedFromEquipmentIsNotUsedInTheNextSession() {
        var setup = listed
        for (item in setup.equipment.filter { it.kind == EquipmentKind.CAR_DATA || it.kind == EquipmentKind.SENSOR }) {
            setup = setup.removeEquipment(item.id)
        }
        val active = SetupJson.decode(SetupJson.encode(setup)).active()
        assertTrue(active.obdBluetooth.isEmpty())
        assertTrue(active.obdWifi.isEmpty())
        assertNull(active.heartRateStrap)
    }

    @Test
    fun removingOneAdapterKeepsTheOther() {
        val bluetooth = listed.ofKind(EquipmentKind.CAR_DATA).first { it.model == EquipmentModel.OBD_BLUETOOTH }
        val active = listed.removeEquipment(bluetooth.id).active()
        assertTrue(active.obdBluetooth.isEmpty())
        assertEquals(1, active.obdWifi.size)
    }

    @Test
    fun theFirstListedStrapRecords() {
        val second = "6F1A2B3C-0000-4000-8000-000000000003"
        val two = listed.addEquipment(EquipmentModel.HEART_RATE, "Garmin HRM", second, ::id)
        assertEquals("Polar H10 1234", two.active().heartRateStrap?.name)
    }

    @Test
    fun aWifiAdapterWithAnUnusableAddressIsLeftOut() {
        val bad = Equipment("x", EquipmentModel.OBD_WIFI, "Wi-Fi OBD-II adapter", "no port")
        assertTrue(SessionEquipment.obdWifi(listOf(bad)).isEmpty())
    }
}
