package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The links held open until the app closes (FBBD-463, FBBD-464) are the devices the next session starts. */
class HeldDevicesTest {
    private var next = 0
    private fun id() = "id${next++}"

    private val setup = Setup.fresh(::id)
        .addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3242", "AA:01", ::id)
        .addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3559", "AA:02", ::id)
        .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "AA:03", ::id)
        .addEquipment(EquipmentModel.OBD_BLUETOOTH, "Konnwei", "AA:04", ::id)
        .addEquipment(EquipmentModel.HEART_RATE, "Polar 8054-1", "AA:05", ::id)
        .addEquipment(EquipmentModel.HEART_RATE, "Polar 808S", "AA:06", ::id)
        .addEquipment(EquipmentModel.TPMS_BRIDGE, "RCTPMS", "AA:07", ::id)

    private fun idOf(s: Setup, name: String) = s.equipment.first { it.name == name }.id
    private fun names(s: Setup, bleObd: (String) -> Boolean = { true }) = heldDevices(s, bleObd).map { it.name }

    @Test
    fun theChosenDevicesAreHeld() {
        val s = setup.chooseAtStart(StartKind.CAR_DATA, idOf(setup, "vLinker MC+"))
        assertEquals(listOf("RaceBox Micro 3242", "vLinker MC+", "Polar 808S", "RCTPMS"), names(s))
    }

    @Test
    fun anotherPickHoldsTheOtherAndLetsTheFirstGo() {
        val s = setup.chooseAtStart(StartKind.GNSS, idOf(setup, "RaceBox Micro 3559"))
            .chooseAtStart(StartKind.HEART_RATE, idOf(setup, "Polar 8054-1"))
        val held = names(s)
        assertTrue("RaceBox Micro 3559" in held && "Polar 8054-1" in held)
        assertTrue("RaceBox Micro 3242" !in held && "Polar 808S" !in held)
    }

    @Test
    fun noneHoldsNothingOfThatKind() {
        val s = setup.chooseAtStart(StartKind.CAR_DATA, null)
            .chooseAtStart(StartKind.HEART_RATE, null)
            .chooseAtStart(StartKind.TPMS, null)
        assertEquals(listOf("RaceBox Micro 3242"), names(s))
    }

    @Test
    fun aClassicObdAdapterIsNotHeld() {
        val s = setup.chooseAtStart(StartKind.CAR_DATA, idOf(setup, "Konnwei"))
        assertTrue("Konnwei" !in names(s) { it != "AA:04" })
    }

    @Test
    fun aGpsNotOnBluetoothLeIsNotHeld() {
        val wifi = setup.addEquipment(EquipmentModel.TCP_GNSS, "Wi-Fi GPS", "192.168.4.1:9000", ::id)
        val s = wifi.chooseAtStart(StartKind.GNSS, idOf(wifi, "Wi-Fi GPS"))
        assertTrue(heldDevices(s).none { it.name == "Wi-Fi GPS" })
    }
}
