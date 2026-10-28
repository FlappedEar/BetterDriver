package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The setup file reads the same on Android and iOS (FBBD-338). */
class SetupJsonCommonTest {
    private var next = 0
    private fun id() = "id${next++}"

    @Test
    fun aSetupSurvivesAWriteAndARead() {
        val setup = Setup.fresh(::id)
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3241", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", ::id)
            .addEquipment(EquipmentModel.HEART_RATE, "Polar H10", "A1B2", ::id)
            .addCar("Scion FR-S", "6AT", ::id)
        val read = SetupJson.decode(SetupJson.encode(setup))
        assertEquals(setup, read)
        assertEquals("id1", read.session.gnssId)
        assertEquals("id3", read.session.carId)
    }

    @Test
    fun aFileThatIsNotASetupIsRefused() {
        assertFailsWith<SetupFormatException> { SetupJson.decode("{\"format\":\"other\"}") }
        assertFailsWith<SetupFormatException> { SetupJson.decode("not json") }
    }

    @Test
    fun aCarsOwnParametersAndScanSurviveAWriteAndARead() {
        // FBBD-344: added fields with defaults, so the file version stays.
        var setup = Setup.fresh(::id).addCar("FR-S", "", ::id)
        val car = setup.cars.single()
        val trim = CarParameter(
            "trim", "Fuel trim", CarParameterSource.OBD, "(A-128)*100.0/128", "%", header = 0x7E0, pid = 0x06,
        )
        val wheel = CarParameter(
            "wheel_rl", "Wheel RL", CarParameterSource.CAN, "bytesToUIntLe(raw, 4, 2)", "km/h", canId = 0x0D4,
        )
        val scan = CarScanRecord(
            1_000, "A6", listOf(0x05, 0x0C), "JF1ZNAA1", canHeard = true,
            canIds = listOf(CanIdRecord(0x0D4, 50.0, 8, listOf(4, 5), "1A003C00E80F007B")),
        )
        val withAll = car.withParameter(trim).withParameter(wheel).copy(scan = scan).recordPid(0x10, true, setOf(0x0C))
        setup = setup.copy(cars = listOf(withAll))
        val read = SetupJson.decode(SetupJson.encode(setup))
        assertEquals(setup, read)
        assertEquals(listOf(0x0C, 0x10), read.cars.single().recordedPids)
        // A file from before has none of them.
        val old = SetupJson.encode(Setup.fresh(::id).addCar("Corolla", "", ::id))
        val oldCar = SetupJson.decode(old).cars.single()
        assertEquals(emptyList(), oldCar.parameters)
        assertEquals(null, oldCar.scan)
        assertEquals(null, oldCar.recordedPids)
    }

    @Test
    fun aParameterIsReplacedByItsKeyAndNewKeysAreFree() {
        val car = CarProfile("c", "FR-S")
        val a = CarParameter("gear", "Gear", CarParameterSource.CAN, "A", canId = 0x3B7)
        val b = a.copy(name = "Gear position")
        val two = car.withParameter(a).withParameter(b)
        assertEquals(listOf(b), two.parameters)
        assertEquals("gear_2", two.newParameterKey("Gear!"))
        assertEquals("wheel_speed_rl", two.newParameterKey("Wheel speed RL"))
        assertEquals("parameter", two.newParameterKey("°"))
        assertEquals("new_2", two.newParameterKey("New"), "\"new\" is the page that adds one")
        assertEquals("draft_2", two.newParameterKey("Draft"))
        assertEquals(emptyList(), two.withoutParameter("gear").parameters)
    }
}
