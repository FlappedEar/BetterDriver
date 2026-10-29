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
        val s = Setup.fresh(ids).addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3241", "AA:BB", ids)
        val a = s.active()
        assertEquals("RaceBox Micro 3241", a.gnss?.name)
        assertNull(a.startProblem)
        assertEquals(listOf(TrackChannel.SPEED), a.tiles)
    }

    @Test
    fun secondReceiverDoesNotTakeOver() {
        val s = Setup()
            .addEquipment(EquipmentModel.RACEBOX, address = "AA", newId = ids)
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = ids)
        assertEquals(EquipmentModel.RACEBOX, s.active().gnss?.model)
    }

    @Test
    fun phoneGpsIsAddedOnceAndChosenLikeAnyReceiver() {
        // FBBD-275, UI-153 B: no address, listed once, the session's receiver when it is the first.
        val s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.PHONE_GPS, newId = ids)
            .addEquipment(EquipmentModel.PHONE_GPS, newId = ids)
        assertEquals(1, s.equipment.count { it.model == EquipmentModel.PHONE_GPS })
        assertEquals("Phone GPS", s.active().gnss?.name)
        assertNull(s.active().startProblem)
        // A RaceBox added later does not take over; chosen, it does.
        val box = s.addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3241", "AA", ids)
        assertEquals(EquipmentModel.PHONE_GPS, box.active().gnss?.model)
        val id = box.equipment.single { it.model == EquipmentModel.RACEBOX }.id
        assertEquals(EquipmentModel.RACEBOX, box.chooseGnss(id).active().gnss?.model)
    }

    @Test
    fun pairedModelsNeedAnAddressAndAreNotListedTwice() {
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.GOPRO, newId = ids) }
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO, "Old", "AA", ids)
            .addEquipment(EquipmentModel.GOPRO, "New", "AA", ids)
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = ids)
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = ids)
        assertEquals(listOf("New", "Phone camera"), s.equipment.map { it.name })
        val boxes = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RB 1", "AA", ids)
            .addEquipment(EquipmentModel.RACEBOX, "RB 2", "BB", ids)
        assertEquals(listOf("RB 1", "RB 2"), boxes.equipment.map { it.name })
    }

    @Test
    fun anyNumberOfGoProsCanBeListed() {
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO, "GoPro 1111", "AA", ids, GoProModel.HERO11_BLACK)
            .addEquipment(EquipmentModel.GOPRO, "GoPro 2222", "BB", ids, GoProModel.HERO13_BLACK)
            .addEquipment(EquipmentModel.GOPRO_FILES, newId = ids, goPro = GoProModel.HERO8_BLACK)
            .addEquipment(EquipmentModel.GOPRO_FILES, newId = ids, goPro = GoProModel.HERO5_BLACK)
        assertEquals(
            listOf("GoPro HERO11 Black", "GoPro HERO13 Black", "GoPro HERO8 Black", "GoPro HERO5 Black"),
            s.equipment.map { it.modelLabel },
        )
        assertEquals(s.equipment.map { it.id }, s.session.cameraIds, "all on for the next session")
        assertEquals(2, s.active().appCameras.size)
        assertEquals(2, s.active().manualCameras.size)
    }

    @Test
    fun otherActionCamerasAreAddedByFiles() {
        val s = Setup()
            .addEquipment(EquipmentModel.CAMERA_FILES, "DJI Action 4", newId = ids)
            .addEquipment(EquipmentModel.CAMERA_FILES, "Insta360", newId = ids)
        assertEquals(listOf("Other action camera", "Other action camera"), s.equipment.map { it.modelLabel })
        assertEquals(listOf("DJI Action 4", "Insta360"), s.active().manualCameras.map { it.name })
        assertTrue(s.active().appCameras.isEmpty())
        assertTrue(s.hasGoPro, "shows the per-camera lines on session cards")
        assertNull(s.equipment.first().goPro)
        assertNull(s.equipment.first().videoMode, "set on the camera itself")
    }

    @Test
    fun bluetoothLeGpsAndHeartRateMonitorsAreListed() {
        val s = Setup()
            .addEquipment(EquipmentModel.LNS_GNSS, "Garmin GLO 2", "AA", ids)
            .addEquipment(EquipmentModel.HEART_RATE, "Polar H10", "BB", ids)
        val active = s.active()
        assertEquals("Garmin GLO 2", active.gnss?.name, "the first receiver is the session's")
        assertEquals(listOf("Polar H10"), active.sensors.map { it.name })
        assertEquals(setOf(TrackChannel.SPEED), channelsFor(active.gnss))
        assertTrue(EquipmentModel.LNS_GNSS.paired && EquipmentModel.HEART_RATE.paired)
        assertEquals(EquipmentKind.SENSOR, s.equipment.last().kind)
    }

    @Test
    fun wifiGpsIsListedByHostAndPort() {
        val s = Setup().addEquipment(EquipmentModel.TCP_GNSS, "Wi-Fi GPS", "192.168.4.1:2000", ids)
        assertEquals("192.168.4.1:2000", s.active().gnss?.address)
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.TCP_GNSS, newId = ids) }
        assertEquals(TcpAddress("gps.local", 10110), TcpAddress.parse(" gps.local ", "10110"))
        assertEquals(TcpAddress("192.168.4.1", 2000), TcpAddress.parse("192.168.4.1:2000"))
        assertNull(TcpAddress.parse("192.168.4.1", "0"))
        assertNull(TcpAddress.parse("192.168.4.1", "70000"))
        assertNull(TcpAddress.parse("", "2000"))
        assertNull(TcpAddress.parse("192.168.4.1"))
        assertTrue(EquipmentModel.DIY_GNSS.paired)
    }

    @Test
    fun vboxSportAndDragyAreListedAsReceivers() {
        val s = Setup()
            .addEquipment(EquipmentModel.VBOX_SPORT, "VBSport 00000300", "AA", ids)
            .addEquipment(EquipmentModel.DRAGY, "DRG70-ABC123", "BB", ids)
        assertEquals("VBSport 00000300", s.active().gnss?.name, "the first receiver is the session's")
        assertEquals(listOf(EquipmentKind.GNSS, EquipmentKind.GNSS), s.equipment.map { it.kind })
        assertEquals(setOf(TrackChannel.SPEED), channelsFor(s.active().gnss))
        assertTrue(EquipmentModel.VBOX_SPORT.paired && EquipmentModel.DRAGY.paired)
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.VBOX_SPORT, newId = ids) }
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.DRAGY, newId = ids) }
    }

    @Test
    fun repairingAGoProKeepsItsItemAndLearnsItsModel() {
        val first = Setup().addEquipment(EquipmentModel.GOPRO, "GoPro 1111", "AA", ids)
        val id = first.equipment.single().id
        assertEquals(GoProModel.OTHER_OPEN_GOPRO, first.equipment.single().goPro)
        val s = first.addEquipment(EquipmentModel.GOPRO, "GoPro 1111", "AA", ids, GoProModel.HERO12_BLACK)
        assertEquals(listOf(id), s.equipment.map { it.id })
        assertEquals(GoProModel.HERO12_BLACK, s.equipment.single().goPro)
    }

    @Test
    fun goProModelsFromTheOpenGoProSpec() {
        assertEquals(GoProModel.HERO13_BLACK, GoProModel.ofModelId(65))
        assertEquals(GoProModel.MAX_2, GoProModel.ofModelId(64))
        assertEquals(GoProModel.HERO9_BLACK, GoProModel.ofModelId(55))
        assertEquals(GoProModel.OTHER_OPEN_GOPRO, GoProModel.ofModelId(99), "a newer camera is still Open GoPro")
        assertEquals(GoProModel.HERO12_BLACK, GoProModel.ofName("HERO12 Black"))
        assertNull(GoProModel.ofName("HERO8 Black"), "no Open GoPro, never reported by one")
        assertTrue(GoProModel.OpenGoPro.all { it.openGoPro })
        assertTrue(GoProModel.FileImport.none { it.openGoPro })
        assertTrue(GoProModel.HERO8_BLACK in GoProModel.FileImport)
        assertTrue(VideoMode(VideoSize.HD, 60) in GoProModel.HERO13_BLACK.videoModes)
        assertFalse(VideoMode(VideoSize.QHD, 60) in GoProModel.MAX_2.videoModes)
        assertTrue(GoProModel.OpenGoPro.all { VideoMode(VideoSize.FHD, 60) in it.videoModes })
    }

    @Test
    fun phoneOffersNo27k() {
        // Arek's pick UI-103 A (2026-10-20, FBBD-246 Won't do): 2.7K is gone from the phone's list.
        assertEquals(
            listOf("4K · 60", "4K · 30", "1080p · 60", "1080p · 30", "720p · 60", "720p · 30"),
            EquipmentModel.PHONE_CAMERA.videoModes.map { it.label },
        )
        assertEquals(VideoMode(VideoSize.FHD, 60), EquipmentModel.PHONE_CAMERA.defaultVideo)
    }

    @Test
    fun aSaved27kPhoneModeBecomes1080p60() {
        val phone = Equipment("p", EquipmentModel.PHONE_CAMERA, "Phone", video = VideoMode(VideoSize.QHD, 60))
        assertEquals(VideoMode(VideoSize.FHD, 60), phone.videoMode)
    }

    @Test
    fun choosingAGoProModel() {
        var s = Setup()
            .addEquipment(EquipmentModel.GOPRO, "GoPro 1", "AA", ids)
            .addEquipment(EquipmentModel.GOPRO_FILES, newId = ids)
        val (open, files) = s.equipment
        s = s.chooseVideo(open.id, VideoMode(VideoSize.UHD, 60))
        assertEquals(s, s.chooseGoPro(open.id, GoProModel.HERO7_BLACK), "no Open GoPro on a HERO7")
        s = s.chooseGoPro(open.id, GoProModel.MAX_2).chooseGoPro(files.id, GoProModel.MAX)
        assertEquals("GoPro MAX2", s.equipment(open.id)?.modelLabel)
        assertEquals(VideoMode(VideoSize.UHD, 60), s.equipment(open.id)?.videoMode)
        assertEquals("GoPro MAX", s.equipment(files.id)?.modelLabel)
        s = s.chooseVideo(open.id, VideoMode(VideoSize.FHD, 30)).chooseGoPro(open.id, GoProModel.HERO13_BLACK)
        assertEquals(VideoMode(VideoSize.FHD, 30), s.equipment(open.id)?.videoMode)
    }

    @Test
    fun raceBoxModelsByAdvertisedName() {
        val s = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini 0042", "AA", ids)
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini S 0043", "BB", ids)
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3241", "CC", ids)
        assertEquals(listOf("RaceBox Mini", "RaceBox Mini S", "RaceBox Micro"), s.equipment.map { it.modelLabel })
        for (box in s.equipment) {
            assertEquals(setOf(TrackChannel.SPEED, TrackChannel.G), channelsFor(box), box.name)
        }
    }

    @Test
    fun phoneGpsOffersGFromThePhonesAccelerometer() {
        val s = Setup().addEquipment(EquipmentModel.PHONE_GPS, newId = ids)
        assertEquals(setOf(TrackChannel.SPEED, TrackChannel.G), channelsFor(s.active().gnss))
    }

    @Test
    fun nmeaReceiverKeepsGForRaceChronoDiyLines() {
        val s = Setup().addEquipment(EquipmentModel.NMEA_GNSS, "Qstarz 818XT", "AA", ids)
            .chooseTiles(listOf(TrackChannel.SPEED, TrackChannel.G))
        assertEquals("Qstarz 818XT", s.active().gnss?.name)
        assertEquals(listOf(TrackChannel.SPEED, TrackChannel.G), s.active().tiles)
        val wifi = Setup().addEquipment(EquipmentModel.TCP_GNSS, "Wi-Fi GPS", "192.168.4.1:2000", ids)
        assertTrue(TrackChannel.G in channelsFor(wifi.active().gnss))
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.NMEA_GNSS, newId = ids) }
    }

    @Test
    fun renamingKeepsTheAdvertisedName() {
        val s = Setup().addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini 0042", "AA", ids)
        val renamed = s.renameEquipment(s.equipment.single().id, "Track box")
        assertEquals("RaceBox Mini 0042", renamed.equipment.single().advertisedName)
    }

    @Test
    fun goProShowsOnlyWhenListedAndStartsOnlyWhenChosen() {
        var s = Setup.fresh(ids).addEquipment(EquipmentModel.GOPRO, "GoPro 11", "AA", ids)
        assertTrue(s.hasGoPro)
        val hero = s.equipment.single { it.model == EquipmentModel.GOPRO }
        assertEquals(listOf(hero), s.active().appCameras.filter { it.model.isGoPro })
        s = s.useCamera(hero.id, false)
        assertFalse(s.active().uses(EquipmentModel.GOPRO))
        assertTrue(s.hasGoPro, "still listed, just not used this session")
        s = s.removeEquipment(hero.id)
        assertFalse(s.hasGoPro)
    }

    @Test
    fun goProWithAddedFilesIsAManualCamera() {
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO_FILES, "GoPro HERO8", newId = ids, goPro = GoProModel.HERO8_BLACK)
        assertEquals(listOf("GoPro HERO8"), s.active().manualCameras.map { it.name })
        assertTrue(s.active().appCameras.isEmpty())
    }

    @Test
    fun removingTheReceiverFallsBackToAnotherOrNone() {
        var s = Setup()
            .addEquipment(EquipmentModel.RACEBOX, address = "AA", newId = ids)
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
            .addEquipment(EquipmentModel.RACEBOX, address = "AA", newId = ids)
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = ids)
            .chooseTiles(listOf(TrackChannel.G, TrackChannel.SPEED, TrackChannel.G, TrackChannel.LAP))
        // A saved Lap tile is left out (UI-82).
        assertEquals(listOf(TrackChannel.G, TrackChannel.SPEED), s.active().tiles)
        val demo = s.ofKind(EquipmentKind.GNSS).single { it.model == EquipmentModel.DEMO_GNSS }
        val raceBox = s.active().gnss!!
        s = s.chooseGnss(demo.id)
        assertEquals(listOf(TrackChannel.SPEED), s.active().tiles, "demo has no accelerometer")
        s = s.chooseGnss(raceBox.id)
        assertEquals(listOf(TrackChannel.G, TrackChannel.SPEED), s.active().tiles)
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
            listOf(EquipmentModel.RACEBOX, EquipmentModel.PHONE_CAMERA, EquipmentModel.GOPRO),
            s.equipment.map { it.model },
        )
        assertEquals(GoProModel.HERO11_BLACK, s.equipment.last().goPro)
        assertEquals("RaceBox Micro 3241", s.active().gnss?.name)
        assertEquals(listOf("Phone camera", "GoPro 1234"), s.active().cameras.map { it.name })
        assertTrue(s.cars.isEmpty(), "the old default car was not a choice")
        assertFalse(s.equipment.any { it.model == EquipmentModel.GOPRO_FILES })
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
        val store = SetupStore(file.path)
        assertNull(store.load())
        val s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.RACEBOX, "RB", "AA", ids)
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
        var s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.GOPRO, address = "AA", newId = ids, goPro = GoProModel.HERO11_BLACK)
        val phone = s.equipment.first { it.model == EquipmentModel.PHONE_CAMERA }
        val hero = s.equipment.first { it.model == EquipmentModel.GOPRO }
        assertEquals(VideoMode(VideoSize.FHD, 60), phone.videoMode)
        assertEquals(VideoMode(VideoSize.FHD, 60), hero.videoMode)

        s = s.chooseVideo(phone.id, VideoMode(VideoSize.HD, 30))
        assertEquals("720p · 30", s.equipment(phone.id)?.videoMode?.label)
        // Pairing the camera again keeps its mode.
        s = s.chooseVideo(hero.id, VideoMode(VideoSize.UHD, 60))
        s = s.addEquipment(EquipmentModel.GOPRO, address = "AA", newId = ids)
        assertEquals(VideoMode(VideoSize.UHD, 60), s.equipment(hero.id)?.videoMode)
    }

    @Test
    fun modesAModelDoesNotOfferAreRefused() {
        var s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.GOPRO, address = "AA", newId = ids, goPro = GoProModel.HERO11_BLACK)
        val hero = s.equipment.first { it.model == EquipmentModel.GOPRO }
        assertEquals(s, s.chooseVideo(hero.id, VideoMode(VideoSize.HD, 60)), "no 720p on a HERO11")
        s = s.addEquipment(EquipmentModel.GOPRO_FILES, newId = ids, goPro = GoProModel.HERO8_BLACK)
        val hero8 = s.equipment.first { it.model == EquipmentModel.GOPRO_FILES }
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

    @Test
    fun readsVersion1FilesWithSingleProducts() {
        val v1 = """
            {"format":"betterdriver-setup","version":1,"setup":{"equipment":[
              {"id":"a","model":"RACEBOX_MICRO","name":"RB","address":"AA","advertisedName":"RaceBox Mini S 0001"},
              {"id":"b","model":"GOPRO_HERO11","name":"Helmet","address":"BB","video":{"size":"UHD","fps":60}},
              {"id":"c","model":"GOPRO_HERO8","name":"Rear"},
              {"id":"d","model":"PHONE_CAMERA","name":"Phone camera"}
            ],"cars":[],"session":{"gnssId":"a","cameraIds":["b","c"]}}}
        """.trimIndent()
        val s = SetupJson.decode(v1)
        assertEquals(
            listOf(
                EquipmentModel.RACEBOX,
                EquipmentModel.GOPRO,
                EquipmentModel.GOPRO_FILES,
                EquipmentModel.PHONE_CAMERA,
                EquipmentModel.OBD_WIFI,
            ),
            s.equipment.map { it.model },
        )
        assertEquals(
            listOf(null, GoProModel.HERO11_BLACK, GoProModel.HERO8_BLACK, null, null),
            s.equipment.map { it.goPro },
        )
        assertEquals("RaceBox Mini S", s.equipment(s.session.gnssId)?.modelLabel)
        assertEquals(VideoMode(VideoSize.UHD, 60), s.equipment("b")?.videoMode)
        assertEquals(s, SetupJson.decode(SetupJson.encode(s)), "written back as version 3")
        assertTrue(SetupJson.encode(s).contains("\"version\": 3"))
    }

    @Test
    fun version2FilesGetTheWifiObdAdapterTheRecorderUsedToLookFor() {
        val v2 = SetupJson.encode(Setup.fresh(ids)).replace("\"version\": 3", "\"version\": 2")
        val s = SetupJson.decode(v2)
        val wifi = s.equipment.last()
        assertEquals(EquipmentModel.OBD_WIFI, wifi.model)
        assertEquals(WifiObdAddress, wifi.address)
        assertEquals(listOf(wifi), s.active().carData)
        // Written back as version 3, it is not added again.
        assertEquals(s, SetupJson.decode(SetupJson.encode(s)))
    }

    @Test
    fun obdAdaptersAreCarDataUsedInEverySessionAndListedOncePerAddress() {
        val s = Setup.fresh(ids)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "AA:BB", ids)
            .addEquipment(EquipmentModel.OBD_BLUETOOTH, "vLinker MC+", "AA:BB", ids)
            .addEquipment(EquipmentModel.OBD_WIFI, address = WifiObdAddress, newId = ids)
        val active = s.active()
        assertEquals(listOf("vLinker MC+", "Wi-Fi OBD-II adapter"), active.carData.map { it.name })
        assertTrue(active.carData.all { it.kind == EquipmentKind.CAR_DATA })
        assertTrue(active.cameras.none { it.kind == EquipmentKind.CAR_DATA })
        assertEquals(null, active.gnss, "an adapter is not a receiver")
        val removed = s.removeEquipment(active.carData.first().id)
        assertEquals(listOf(EquipmentModel.OBD_WIFI), removed.active().carData.map { it.model })
        assertFailsWith<IllegalArgumentException> { Setup().addEquipment(EquipmentModel.OBD_BLUETOOTH, newId = ids) }
    }
}
