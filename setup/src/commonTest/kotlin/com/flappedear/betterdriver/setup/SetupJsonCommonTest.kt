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
}
