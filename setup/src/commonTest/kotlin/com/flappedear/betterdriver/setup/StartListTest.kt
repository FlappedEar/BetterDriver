package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The list at START (FBBD-446): every listed item, one row each, chosen the same way on Android and iOS. */
class StartListTest {
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

    private fun group(s: Setup, kind: StartKind) = startGroups(s).first { it.kind == kind }
    private fun on(s: Setup, kind: StartKind) = group(s, kind).rows.filter { it.on }.map { it.name }
    private fun idOf(s: Setup, name: String) = s.equipment.first { it.name == name }.id

    @Test
    fun everyListedItemIsInItsGroupInOrder() {
        val groups = startGroups(setup)
        assertEquals(StartKind.entries, groups.map { it.kind })
        assertEquals(listOf("Phone camera"), group(setup, StartKind.CAMERA).rows.map { it.name })
        assertEquals(listOf("Polar 8054-1", "Polar 808S"), group(setup, StartKind.HEART_RATE).rows.map { it.name })
        assertEquals(listOf("RCTPMS"), group(setup, StartKind.TPMS).rows.map { it.name })
        assertTrue(groups.filter { it.kind != StartKind.CAMERA }.all { it.oneOf })
    }

    @Test
    fun theListShowsWhatTheNextSessionUses() {
        assertEquals(listOf("RaceBox Micro 3242"), on(setup, StartKind.GNSS))
        assertEquals(listOf("vLinker MC+"), on(setup, StartKind.CAR_DATA))
        assertEquals(listOf("Polar 808S"), on(setup, StartKind.HEART_RATE))
        assertEquals(listOf("RCTPMS"), on(setup, StartKind.TPMS))
        assertEquals(listOf("Phone camera"), on(setup, StartKind.CAMERA))
        assertEquals("1080p · 60", group(setup, StartKind.CAMERA).rows.single().detail)
    }

    @Test
    fun choosingOneRecordsOnlyThatOneAndSurvivesTheSetupFile() {
        val chosen = setup
            .chooseAtStart(StartKind.HEART_RATE, idOf(setup, "Polar 8054-1"))
            .chooseAtStart(StartKind.CAR_DATA, idOf(setup, "Konnwei"))
            .chooseAtStart(StartKind.GNSS, idOf(setup, "RaceBox Micro 3559"))
        val active = SetupJson.decode(SetupJson.encode(chosen)).active()
        assertEquals("Polar 8054-1", active.heartRateStrap?.name)
        assertEquals(listOf("Konnwei"), active.carData.map { it.name })
        assertEquals(listOf("Konnwei"), active.obdBluetooth.map { it.name })
        assertEquals("RaceBox Micro 3559", active.gnss?.name)
        assertEquals(listOf("Polar 8054-1", "RCTPMS"), active.sensors.map { it.name })
        assertEquals(listOf("Polar 8054-1"), on(chosen, StartKind.HEART_RATE))
    }

    @Test
    fun noneRecordsNothingOfThatKind() {
        val none = setup
            .chooseAtStart(StartKind.HEART_RATE, null)
            .chooseAtStart(StartKind.CAR_DATA, null)
            .chooseAtStart(StartKind.TPMS, null)
            .chooseAtStart(StartKind.CAMERA, idOf(setup, "Phone camera"), on = false)
        val active = SetupJson.decode(SetupJson.encode(none)).active()
        assertNull(active.heartRateStrap)
        assertNull(active.tpmsBridge)
        assertTrue(active.carData.isEmpty() && active.sensors.isEmpty() && active.cameras.isEmpty())
        assertTrue(group(none, StartKind.HEART_RATE).noneOn)
        assertTrue(group(none, StartKind.CAR_DATA).noneOn)
        // The choice is kept (UI-313 A): a device added where None was chosen is chosen.
        val added = none.addEquipment(EquipmentModel.TPMS_BRIDGE, "Second bridge", "AA:08", ::id)
        assertEquals("Second bridge", added.active().tpmsBridge?.name)
    }

    @Test
    fun removingTheChosenItemFallsBackToWhatIsLeft() {
        val chosen = setup.chooseAtStart(StartKind.CAR_DATA, idOf(setup, "Konnwei"))
        val removed = chosen.removeEquipment(idOf(setup, "Konnwei"))
        assertEquals(listOf("vLinker MC+"), removed.active().carData.map { it.name })
        assertNull(removed.session.carDataId)
    }

    @Test
    fun aSetupSavedBeforeTheListKeepsTodaysBehaviour() {
        val old = setup.copy(session = setup.session.copy(heartRateId = null, carDataId = null, tpmsId = null))
        val active = old.active()
        assertEquals(listOf("vLinker MC+", "Konnwei"), active.carData.map { it.name })
        assertEquals("Polar 808S", active.heartRateStrap?.name)
        assertEquals("RCTPMS", active.tpmsBridge?.name)
    }

    @Test
    fun aCameraStartedByHandSaysSo() {
        val s = setup.addEquipment(EquipmentModel.GOPRO_FILES, "HERO8", newId = ::id)
        assertEquals("Start by hand", group(s, StartKind.CAMERA).rows.first { it.name == "HERO8" }.detail)
    }
}
