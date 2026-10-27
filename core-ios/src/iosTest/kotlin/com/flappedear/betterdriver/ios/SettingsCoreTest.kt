package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.setup.EquipmentModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What the iPhone's Settings calls (FBBD-338). */
class SettingsCoreTest {
    @Test
    fun aFreshSetupHasThePhoneCameraOnly() {
        val setup = SettingsCore.freshSetup("cam")
        assertEquals(listOf(EquipmentModel.PHONE_CAMERA), setup.equipment.map { it.model })
        assertEquals(listOf("cam"), setup.session.cameraIds)
    }

    @Test
    fun theSetupTextReadsBack() {
        val setup = SettingsCore.addCar(SettingsCore.freshSetup("cam"), "FR-S", "", "car")
        assertEquals(setup, SettingsCore.decodeSetup(SettingsCore.encodeSetup(setup)))
        assertNull(SettingsCore.decodeSetup("{}"))
    }

    @Test
    fun blankNamesAndMissingAddressesChangeNothing() {
        val setup = SettingsCore.freshSetup("cam")
        assertSame(setup, SettingsCore.addCar(setup, " ", "", "car"))
        val raceBox = SettingsCore.model("RACEBOX")!!
        assertSame(setup, SettingsCore.addEquipment(setup, raceBox, "RaceBox", null, "rb"))
        val gps = SettingsCore.addEquipment(setup, SettingsCore.model("PHONE_GPS")!!, "Phone GPS", null, "gps")
        assertEquals("gps", gps.session.gnssId)
    }

    @Test
    fun coachSettingsReadBackAndDefault() {
        val s = SettingsCore.withLanguage(
            SettingsCore.withVerbosity(SettingsCore.coachSettings(null), Verbosity.LapTimes),
            CoachLanguage.Polish,
        )
        val read = SettingsCore.coachSettings(SettingsCore.coachText(s))
        assertEquals(Verbosity.LapTimes, read.verbosity)
        assertEquals(CoachLanguage.Polish, read.language)
        assertEquals(Verbosity.Focus, SettingsCore.coachSettings("garbage \\u12").verbosity)
    }

    @Test
    fun thePhoneCameraIsTurnedOffAndItsModeSet() {
        val setup = SettingsCore.freshSetup("cam")
        assertEquals(true, SettingsCore.phoneCameraOn(setup))
        assertEquals("1080p · 60", SettingsCore.phoneVideoLabel(setup))
        assertEquals("Phone camera on", SettingsCore.camerasLine(setup))
        val off = SettingsCore.withPhoneCamera(setup, false)
        assertEquals(false, SettingsCore.phoneCameraOn(off))
        assertEquals("Phone camera off", SettingsCore.camerasLine(off))
        assertEquals("4K · 30", SettingsCore.phoneVideoLabel(SettingsCore.withPhoneVideo(setup, "4K · 30")))
        assertSame(setup, SettingsCore.withPhoneVideo(setup, "8K · 120"))
    }

    @Test
    fun theDelayIosReportsReplacesTheSetOne() {
        val set = SettingsCore.withDelay(SettingsCore.coachSettings(null), 300)
        assertEquals(180, SettingsCore.sessionCoach(set, 180).audioDelayMillis)
        assertEquals(300, SettingsCore.sessionCoach(set, 0).audioDelayMillis)
        assertEquals(false, SettingsCore.coachOn(SettingsCore.withVerbosity(set, Verbosity.Off)))
    }

    /** Nothing a file holds ends the app: anything that is not a setup reads as none (FBBD-338). */
    @Test
    fun brokenSetupFilesReadAsNone() {
        for (text in listOf("", " ", "[]", "null", "{", "{\"format\":\"betterdriver-setup\"}",
            "{\"format\":\"betterdriver-setup\",\"version\":\"x\",\"setup\":{}}",
            "{\"format\":\"betterdriver-setup\",\"version\":99,\"setup\":{}}",
            "{\"format\":\"betterdriver-setup\",\"version\":3,\"setup\":[]}")) {
            assertNull(SettingsCore.decodeSetup(text), text)
        }
        assertEquals(SettingsCore.coachSettings(null), SettingsCore.coachSettings("verbosity=Loud\nlearningLaps=-4\n\u0000"))
    }

    /** An Android version 1 file, its RaceBox at a Bluetooth MAC address, reads on the iPhone. */
    @Test
    fun anAndroidVersion1FileReads() {
        val v1 = """
            {"format":"betterdriver-setup","version":1,"setup":{"equipment":[
              {"id":"a","model":"RACEBOX_MICRO","name":"RB","address":"AA:BB:CC:DD:EE:FF"},
              {"id":"d","model":"PHONE_CAMERA","name":"Phone camera"}
            ],"cars":[],"session":{"gnssId":"a","cameraIds":["d"]}}}
        """.trimIndent()
        val setup = SettingsCore.decodeSetup(v1)
        assertEquals("a", setup?.active()?.gnss?.id)
        assertEquals(true, setup?.let { SettingsCore.phoneCameraOn(it) })
    }
}
