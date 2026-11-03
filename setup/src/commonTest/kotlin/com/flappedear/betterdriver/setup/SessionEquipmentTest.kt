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
    fun aStrapAddedSecondRecordsAndSurvivesTheSetupFile() {
        // FBBD-445: Arek added a Polar 808S next to the listed 8054-1 and the app kept trying the 8054-1.
        val second = "6F1A2B3C-0000-4000-8000-000000000003"
        val two = listed.addEquipment(EquipmentModel.HEART_RATE, "Polar 808S", second, ::id)
        assertEquals(2, two.ofKind(EquipmentKind.SENSOR).size)
        assertEquals("Polar 808S", SetupJson.decode(SetupJson.encode(two)).active().heartRateStrap?.name)
        assertEquals(second, two.active().heartRateStrap?.address)
    }

    @Test
    fun theChosenStrapRecordsAndRemovingItFallsBackToTheOther() {
        val two = listed.addEquipment(EquipmentModel.HEART_RATE, "Polar 808S", "6F1A2B3C-0000-4000-8000-000000000003", ::id)
        val first = two.ofKind(EquipmentKind.SENSOR).first { it.name == "Polar H10 1234" }
        val second = two.ofKind(EquipmentKind.SENSOR).first { it.name == "Polar 808S" }
        assertEquals("Polar H10 1234", two.chooseHeartRate(first.id).active().heartRateStrap?.name)
        assertEquals("Polar H10 1234", two.removeEquipment(second.id).active().heartRateStrap?.name)
        // Adding a listed strap again picks it.
        val again = two.chooseHeartRate(first.id)
            .addEquipment(EquipmentModel.HEART_RATE, "Polar 808S", "6F1A2B3C-0000-4000-8000-000000000003", ::id)
        assertEquals("Polar 808S", again.active().heartRateStrap?.name)
        // Not a strap: ignored.
        assertEquals(two, two.chooseHeartRate(two.equipment.first { it.model == EquipmentModel.OBD_WIFI }.id))
    }

    @Test
    fun aSetupSavedBeforeTheChoiceRecordsTheStrapAddedLast() {
        // Arek's phone: 8054-1 then 808S listed by 1.31.0, which saved no choice.
        val two = listed.addEquipment(EquipmentModel.HEART_RATE, "Polar 808S", "6F1A2B3C-0000-4000-8000-000000000003", ::id)
        val old = two.copy(session = two.session.copy(heartRateId = null))
        assertEquals("Polar 808S", SetupJson.decode(SetupJson.encode(old)).active().heartRateStrap?.name)
    }

    @Test
    fun theListedTyrePressureBridgeRecordsAndSurvivesTheSetupFile() {
        assertNull(listed.active().tpmsBridge)
        val withBridge = listed.addEquipment(EquipmentModel.TPMS_BRIDGE, "RC TPMS AMOLED", "AA:BB:CC:DD:EE:FF", ::id)
        val active = SetupJson.decode(SetupJson.encode(withBridge)).active()
        assertEquals("RC TPMS AMOLED", active.tpmsBridge?.name)
        // The strap is still the strap: the bridge is a sensor of its own model.
        assertEquals("Polar H10 1234", active.heartRateStrap?.name)
        assertNull(active.copy(sensors = emptyList()).tpmsBridge)
    }

    @Test
    fun aWifiAdapterWithAnUnusableAddressIsLeftOut() {
        val bad = Equipment("x", EquipmentModel.OBD_WIFI, "Wi-Fi OBD-II adapter", "no port")
        assertTrue(SessionEquipment.obdWifi(listOf(bad)).isEmpty())
    }

    @Test
    fun theBluetoothAdapterIsShownWhenTheEarlierWifiOneIsListedFirst() {
        // FBBD-423: the Wi-Fi adapter listed for an earlier setup comes before the vLinker added later.
        val wifiFirst = Setup.fresh(::id)
            .addEquipment(EquipmentModel.OBD_WIFI, "Wi-Fi OBD-II adapter", WifiObdAddress, ::id)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "6F1A2B3C-0000-4000-8000-000000000002", ::id)
        assertEquals(EquipmentModel.OBD_WIFI, wifiFirst.active().carData.first().model)
        assertEquals("vLinker MC+", wifiFirst.active().obdShownFor(null)?.name)
        val onlyWifi = wifiFirst.removeEquipment(wifiFirst.active().obdBluetooth.single().id)
        assertEquals(EquipmentModel.OBD_WIFI, onlyWifi.active().obdShownFor(null)?.model)
        assertNull(SessionEquipment.obdShown(emptyList()))
    }

    @Test
    fun theAdapterInUseIsShown() {
        // Two Bluetooth adapters listed; the second one answered, so the page names it.
        val two = Setup.fresh(::id)
            .addEquipment(EquipmentModel.OBD_WIFI, "Wi-Fi OBD-II adapter", WifiObdAddress, ::id)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "6F1A2B3C-0000-4000-8000-000000000002", ::id)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "KONNWEI", "6F1A2B3C-0000-4000-8000-000000000004", ::id)
            .active()
        assertEquals("KONNWEI", two.obdShownFor("KONNWEI")?.name)
        assertEquals(EquipmentModel.OBD_WIFI, two.obdShownFor("Wi-Fi OBD-II adapter")?.model)
        // A name no listed item has (an unlisted adapter cannot connect) falls back to the first tried.
        assertEquals("vLinker MC+", two.obdShownFor("OBDII")?.name)
        val advertised =
            Equipment("a", EquipmentModel.OBD_BLUETOOTH, "My vLinker", "AA:BB", advertisedName = "vLinker MC-123")
        assertEquals("My vLinker", SessionEquipment.obdShown(listOf(advertised), "vLinker MC-123")?.name)
    }
}
