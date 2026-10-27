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
}
