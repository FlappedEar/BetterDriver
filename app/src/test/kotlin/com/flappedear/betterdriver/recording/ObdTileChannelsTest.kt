package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.setup.TileIds
import com.flappedear.betterdriver.setup.TrackScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FBBD-283: the OBD-II channels on the track screen's tiles are read on top of the driver's set. */
class ObdTileChannelsTest {
    @Test
    fun `OBD tiles on both screens are read, other tiles are not OBD channels`() {
        val screens = TrackScreens(
            main = listOf(TileIds.SPEED, "obd_maf", null, TileIds.HEART_RATE),
            second = listOf("obd_coolant_temp", TileIds.G, "obd_module_voltage"),
        )
        assertEquals(setOf("maf", "coolant_temp", "module_voltage"), obdTileChannels(screens))
        val ids = PollSet.Driver.plus(obdTileChannels(screens)).ids!!
        assertTrue(ids.toString(), "maf" in ids && "module_voltage" in ids && "rpm" in ids)
    }

    @Test
    fun `a new install's screens add nothing`() {
        assertEquals(emptySet<String>(), obdTileChannels(TrackScreens()))
    }

    @Test
    fun `only a first-generation FR-S, 86 or BRZ profile is listened to`() {
        // FBBD-284, Codex on #284: the gen-2 GR86 / BRZ (2022 on) has other CAN frames.
        for (name in listOf("Scion FR-S", "2013 Scion FR-S", "Toyota GT86", "Subaru BRZ 2016", "Toyota 86")) {
            assertTrue(name, isBroadcastCar(name))
        }
        for (name in listOf("2023 Toyota Corolla", "Toyota GR86", "2022 Subaru BRZ", "Mazda MX-5")) {
            assertTrue(name, !isBroadcastCar(name))
        }
    }
}
