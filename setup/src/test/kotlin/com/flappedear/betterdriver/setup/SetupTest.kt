package com.flappedear.betterdriver.setup

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupTest {
    private var next = 0
    private val ids: () -> String = { "id${++next}" }

    @Test
    fun freshInstallHasOnlyThePhoneCamera() {
        val s = Setup.fresh(ids)
        assertEquals(listOf(EquipmentModel.PHONE_CAMERA), s.equipment.map { it.model })
        assertTrue(s.cars.isEmpty())
        assertFalse(s.hasGoPro)
        val a = s.active()
        assertNull(a.car)
        assertNull(a.gnss)
        assertEquals(StartProblem.NO_GNSS, a.startProblem)
        assertEquals(listOf("Phone camera"), a.cameras.map { it.name })
        assertTrue(a.tiles.isEmpty(), "no receiver, nothing to show")
    }

    @Test
    fun firstReceiverIsChosenAndCanStart() {
        val s = Setup.fresh(ids).addEquipment(EquipmentModel.RACEBOX_MICRO, "RaceBox Micro 3241", "AA:BB", ids)
        val a = s.active()
        assertEquals("RaceBox Micro 3241", a.gnss?.name)
        assertNull(a.startProblem)
        assertEquals(listOf(TrackChannel.SPEED, TrackChannel.LAP), a.tiles)
    }

    @Test
    fun secondReceiverDoesNotTakeOver() {
        val s = Setup()
            .addEquipment(EquipmentModel.RACEBOX_MICRO, address = "AA", newId = ids)
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = ids)
        assertEquals(EquipmentModel.RACEBOX_MICRO, s.active().gnss?.model)
    }

    @Test
    fun pairedModelsNeedAnAddressAndAreNotListedTwice() {
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.GOPRO_HERO11, newId = ids) }
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO_HERO11, "Old", "AA", ids)
            .addEquipment(EquipmentModel.GOPRO_HERO11, "New", "AA", ids)
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = ids)
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = ids)
        assertEquals(listOf("New", "Phone camera"), s.equipment.map { it.name })
        val boxes = Setup()
            .addEquipment(EquipmentModel.RACEBOX_MICRO, "RB 1", "AA", ids)
            .addEquipment(EquipmentModel.RACEBOX_MICRO, "RB 2", "BB", ids)
        assertEquals(listOf("RB 1", "RB 2"), boxes.equipment.map { it.name })
    }

    @Test
    fun pairingAnotherHero11ReplacesTheListedOne() {
        val first = Setup().addEquipment(EquipmentModel.GOPRO_HERO11, "GoPro 1111", "AA", ids)
        val id = first.equipment.single().id
        val s = first.addEquipment(EquipmentModel.GOPRO_HERO11, "GoPro 2222", "BB", ids)
        assertEquals(listOf(Equipment(id, EquipmentModel.GOPRO_HERO11, "GoPro 2222", "BB")), s.equipment)
        assertEquals(listOf(id), s.session.cameraIds, "still on for the session")
    }

    @Test
    fun goProShowsOnlyWhenListedAndStartsOnlyWhenChosen() {
        var s = Setup.fresh(ids).addEquipment(EquipmentModel.GOPRO_HERO11, "GoPro 11", "AA", ids)
        assertTrue(s.hasGoPro)
        val hero = s.equipment.single { it.model == EquipmentModel.GOPRO_HERO11 }
        assertEquals(listOf(hero), s.active().appCameras.filter { it.model.isGoPro })
        s = s.useCamera(hero.id, false)
        assertFalse(s.active().uses(EquipmentModel.GOPRO_HERO11))
        assertTrue(s.hasGoPro, "still listed, just not used this session")
        s = s.removeEquipment(hero.id)
        assertFalse(s.hasGoPro)
    }

    @Test
    fun hero8IsAManualCamera() {
        val s = Setup().addEquipment(EquipmentModel.GOPRO_HERO8, newId = ids)
        assertEquals(listOf("GoPro HERO8"), s.active().manualCameras.map { it.name })
        assertTrue(s.active().appCameras.isEmpty())
    }

    @Test
    fun removingTheReceiverFallsBackToAnotherOrNone() {
        var s = Setup()
            .addEquipment(EquipmentModel.RACEBOX_MICRO, address = "AA", newId = ids)
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = ids)
        s = s.removeEquipment(s.active().gnss!!.id)
        assertEquals(EquipmentModel.DEMO_GNSS, s.active().gnss?.model)
        s = s.removeEquipment(s.active().gnss!!.id)
        assertNull(s.active().gnss)
    }

    @Test
    fun removedCameraLeavesTheSession() {
        var s = Setup.fresh(ids)
        val phone = s.equipment.single()
        s = s.removeEquipment(phone.id)
        assertTrue(s.session.cameraIds.isEmpty())
        assertTrue(s.active().cameras.isEmpty())
    }

    @Test
    fun carsAreAddedEditedAndRemoved() {
        var s = Setup().addCar("2023 Toyota Corolla", newId = ids).addCar("2013 Scion FR-S", newId = ids)
        assertEquals("2023 Toyota Corolla", s.active().car?.name, "first car is chosen")
        val frs = s.cars.single { it.name.contains("FR-S") }
        s = s.chooseCar(frs.id).updateCar(frs.copy(name = " FR-S ", notes = "track car"))
        assertEquals(CarProfile(frs.id, "FR-S", "track car"), s.active().car)
        s = s.removeCar(frs.id)
        assertNull(s.active().car)
        assertEquals(listOf("2023 Toyota Corolla"), s.cars.map { it.name })
        assertFailsWith<IllegalArgumentException> { s.addCar(" ", newId = ids) }
    }

    @Test
    fun choosingUnknownIdsChangesNothing() {
        val s = Setup.fresh(ids)
        assertEquals(s, s.chooseCar("nope"))
        assertEquals(s, s.chooseGnss(s.equipment.single().id), "a camera is not a receiver")
        assertEquals(s, s.useCamera("nope", true))
    }

    @Test
    fun tilesFollowTheReceiverAndAreKeptAcrossSwitches() {
        var s = Setup()
            .addEquipment(EquipmentModel.RACEBOX_MICRO, address = "AA", newId = ids)
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = ids)
            .chooseTiles(listOf(TrackChannel.G, TrackChannel.SPEED, TrackChannel.G, TrackChannel.LAP))
        assertEquals(listOf(TrackChannel.G, TrackChannel.SPEED, TrackChannel.LAP), s.active().tiles)
        val demo = s.ofKind(EquipmentKind.GNSS).single { it.model == EquipmentModel.DEMO_GNSS }
        val raceBox = s.active().gnss!!
        s = s.chooseGnss(demo.id)
        assertEquals(listOf(TrackChannel.SPEED, TrackChannel.LAP), s.active().tiles, "demo has no accelerometer")
        s = s.chooseGnss(raceBox.id)
        assertEquals(listOf(TrackChannel.G, TrackChannel.SPEED, TrackChannel.LAP), s.active().tiles)
    }

    @Test
    fun renameKeepsAName() {
        var s = Setup.fresh(ids)
        val id = s.equipment.single().id
        s = s.renameEquipment(id, "Dash cam")
        assertEquals("Dash cam", s.equipment.single().name)
        s = s.renameEquipment(id, "  ")
        assertEquals("Phone camera", s.equipment.single().name)
    }

    @Test
    fun migratesWhatWasActuallySetUp() {
        val s = migrate(
            LegacySettings(
                raceBoxAddress = "AA", raceBoxName = "RaceBox Micro 3241",
                goProAddress = "BB", goProName = "GoPro 1234",
                phoneCamera = null, car = null,
            ),
            ids,
        )
        assertEquals(
            listOf(EquipmentModel.RACEBOX_MICRO, EquipmentModel.PHONE_CAMERA, EquipmentModel.GOPRO_HERO11),
            s.equipment.map { it.model },
        )
        assertEquals("RaceBox Micro 3241", s.active().gnss?.name)
        assertEquals(listOf("Phone camera", "GoPro 1234"), s.active().cameras.map { it.name })
        assertTrue(s.cars.isEmpty(), "the old default car was not a choice")
        assertFalse(s.equipment.any { it.model == EquipmentModel.GOPRO_HERO8 })
    }

    @Test
    fun migratesPhoneCameraOffAndAChosenCar() {
        val s = migrate(LegacySettings(phoneCamera = false, car = "2013 Scion FR-S"), ids)
        assertEquals(listOf(EquipmentModel.PHONE_CAMERA), s.equipment.map { it.model })
        assertTrue(s.active().cameras.isEmpty())
        assertEquals("2013 Scion FR-S", s.active().car?.name)
        assertNull(s.active().gnss)
    }

    @Test
    fun storeRoundTripsAndSetsAsideUnreadableFiles(@TempDir dir: File) {
        val file = File(dir, "setup.json")
        val store = SetupStore(file)
        assertNull(store.load())
        val s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.RACEBOX_MICRO, "RB", "AA", ids)
            .addCar("FR-S", newId = ids)
            .chooseTiles(listOf(TrackChannel.LAP))
        store.save(s)
        assertEquals(s, store.load())

        file.writeText("{ not json")
        assertNull(store.load())
        assertFalse(file.exists())
        assertEquals(1, store.setAsideFiles().size)
    }

    @Test
    fun refusesNewerOrForeignFiles() {
        assertFailsWith<SetupFormatException> {
            SetupJson.decode("""{"format":"betterdriver-setup","version":99,"setup":{}}""")
        }
        assertFailsWith<SetupFormatException> {
            SetupJson.decode("""{"format":"betterdriver-track","version":1,"track":{}}""")
        }
    }

    @Test
    fun ignoresUnknownFieldsFromLaterVersions() {
        val setup = Setup.fresh { "phone" }
        val text = SetupJson.encode(setup).replace("\"cars\"", "\"later\": 1, \"cars\"")
        assertEquals(setup, SetupJson.decode(text))
    }

    @Test
    fun camerasDefaultTo1080p60AndKeepTheChosenMode() {
        var s = Setup.fresh(ids).addEquipment(EquipmentModel.GOPRO_HERO11, address = "AA", newId = ids)
        val phone = s.equipment.first { it.model == EquipmentModel.PHONE_CAMERA }
        val hero = s.equipment.first { it.model == EquipmentModel.GOPRO_HERO11 }
        assertEquals(VideoMode(VideoSize.FHD, 60), phone.videoMode)
        assertEquals(VideoMode(VideoSize.FHD, 60), hero.videoMode)

        s = s.chooseVideo(phone.id, VideoMode(VideoSize.HD, 30))
        assertEquals("720p · 30", s.equipment(phone.id)?.videoMode?.label)
        // Pairing the HERO11 again keeps its mode.
        s = s.chooseVideo(hero.id, VideoMode(VideoSize.UHD, 60))
        s = s.addEquipment(EquipmentModel.GOPRO_HERO11, address = "BB", newId = ids)
        assertEquals(VideoMode(VideoSize.UHD, 60), s.equipment(hero.id)?.videoMode)
    }

    @Test
    fun modesAModelDoesNotOfferAreRefused() {
        var s = Setup.fresh(ids).addEquipment(EquipmentModel.GOPRO_HERO11, address = "AA", newId = ids)
        val hero = s.equipment.first { it.model == EquipmentModel.GOPRO_HERO11 }
        assertEquals(s, s.chooseVideo(hero.id, VideoMode(VideoSize.HD, 60)), "no 720p on a HERO11")
        s = s.addEquipment(EquipmentModel.GOPRO_HERO8, newId = ids)
        val hero8 = s.equipment.first { it.model == EquipmentModel.GOPRO_HERO8 }
        assertNull(hero8.videoMode, "a HERO8 is set by hand")
        assertEquals(s, s.chooseVideo(hero8.id, VideoMode(VideoSize.FHD, 60)))
    }

    @Test
    fun videoModeSurvivesTheFileAndOldFilesReadWithoutIt() {
        val s = Setup.fresh(ids).let { it.chooseVideo(it.equipment.single().id, VideoMode(VideoSize.UHD, 30)) }
        assertEquals(s, SetupJson.decode(SetupJson.encode(s)))
        val old = SetupJson.encode(Setup.fresh(ids)).replace(Regex(""",?\s*"video": null"""), "")
        assertFalse(old.contains("\"video\""))
        assertEquals(VideoMode(VideoSize.FHD, 60), SetupJson.decode(old).equipment.single().videoMode)
    }
}
