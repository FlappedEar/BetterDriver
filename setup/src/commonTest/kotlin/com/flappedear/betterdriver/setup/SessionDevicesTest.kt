package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/** Drive's "This session" card (UI-308 A, FBBD-421), the same on Android and iOS. */
class SessionDevicesTest {
    private var next = 0
    private fun id() = "e${next++}"

    @Test
    fun theCardNamesTheReceiverThenTheCarDataThenTheSensors() {
        val setup = Setup()
            .addEquipment(EquipmentModel.HEART_RATE, "Polar H10 1234", "6F1A2B3C-0000-4000-8000-000000000001", ::id)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "6F1A2B3C-0000-4000-8000-000000000002", ::id)
        assertEquals(listOf("vLinker MC+", "Polar H10 1234"), sessionDeviceNames(setup, null))
        // iOS picks the receiver at START: the one used last names it.
        assertEquals(listOf("RaceBox 3242", "vLinker MC+", "Polar H10 1234"), sessionDeviceNames(setup, "RaceBox 3242"))
        assertEquals(listOf("vLinker MC+", "Polar H10 1234"), sessionDeviceNames(setup, " "))
        assertEquals("No cameras", camerasLine(setup))
    }

    @Test
    fun theCameraLineCountsTheCamerasOn() {
        assertEquals("No cameras", camerasLine(emptyList<CameraSwitch>()))
        assertEquals("Phone camera on", camerasLine(listOf(CameraSwitch("Phone camera", true))))
        assertEquals("Phone camera off", camerasLine(listOf(CameraSwitch("Phone camera", false))))
        val two = listOf(CameraSwitch("Phone camera", true), CameraSwitch("GoPro HERO11", false))
        assertEquals("1 of 2 cameras on", camerasLine(two))
    }

    @Test
    fun oneDeviceIsSingular() {
        assertEquals("device", devicesUnit(1))
        assertEquals("devices", devicesUnit(0))
        assertEquals("devices", devicesUnit(3))
    }
}
