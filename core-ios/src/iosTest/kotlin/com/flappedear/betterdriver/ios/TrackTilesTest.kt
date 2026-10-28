package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TileIds
import com.flappedear.betterdriver.timing.live.LiveTimingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackTilesTest {
    private val live = LiveSnapshot(
        sessionId = "s",
        startUtcMillis = 0,
        elapsedMicros = 1_000_000,
        fixes = 100,
        fixRateHz = 25,
        lastFixAgeMicros = 40_000,
        sats = 14,
        speedKmh = 141.6,
        storageLowBytes = null,
        stoppedReason = null,
        droppedSamples = 0,
        gCombined = 0.876,
        receiverRateHz = 25,
        channels = mapOf("obd_coolant_temp" to 91.4, "obd_battery_voltage" to Double.NaN),
    )

    @Test
    fun tilesReadAsOnAndroid() {
        val speed = TrackTiles.tile(TileIds.SPEED, live, null, receiverHasG = true)!!
        assertEquals("142", speed.value)
        assertEquals("km/h", speed.unit)
        assertEquals("0.88", TrackTiles.tile(TileIds.G, live, null, true)!!.value)
        assertEquals("91", TrackTiles.tile("obd_coolant_temp", live, null, true)!!.value)
        // A channel that stopped sending shows a dash, never its old value.
        val stale = TrackTiles.tile("obd_battery_voltage", live, null, true)!!
        assertEquals(TrackTiles.NO_VALUE, stale.value)
        assertTrue(stale.hasData)
        assertNull(TrackTiles.tile(null, live, null, true))
        assertNull(TrackTiles.tile("", live, null, true))
    }

    @Test
    fun tilesWithoutASourceAreLeftOut() {
        assertFalse(TrackTiles.tile(TileIds.G, live, null, receiverHasG = false)!!.hasData)
        assertFalse(TrackTiles.tile(TileIds.DELTA, live, null, true)!!.hasData)
        assertFalse(TrackTiles.tile(TileIds.HEART_RATE, live, null, true)!!.hasData)
        assertTrue(TrackTiles.tile(TileIds.SPEED, null, null, false)!!.hasData)
    }

    @Test
    fun aRunIsNamedSo() {
        val timing = LiveTimingState.empty(0, pointToPoint = true).copy(lapNumber = 2)
        val tile = TrackTiles.tile(TileIds.LAP_NUMBER, live, timing, true)!!
        assertEquals("Run", tile.label)
        assertEquals("2", tile.value)
    }

    @Test
    fun eachCarHasItsOwnLayout() {
        val setup = Setup(cars = listOf(CarProfile("frs", "FR-S"), CarProfile("corolla", "Corolla")))
        val changed = TrackTiles.choose(setup, "frs", second = false, slot = 2, id = "obd_coolant_temp")
        val id = TrackTiles.ids(changed, "frs", false)[2]
        assertEquals("obd_coolant_temp", TrackTiles.tile(id, live, null, true)?.id)
        assertEquals("", TrackTiles.ids(changed, "corolla", false)[2])
        assertEquals(6, TrackTiles.ids(changed, "frs", false).size)
        assertEquals(12, TrackTiles.ids(changed, "frs", true).size)
        // A slot out of range and an unknown car change nothing and do not crash.
        assertEquals(changed, TrackTiles.choose(changed, "frs", false, 99, TileIds.G))
        TrackTiles.choose(changed, "gone", false, 0, TileIds.G)
        assertEquals("", TrackTiles.ids(TrackTiles.reset(changed, "frs"), "frs", false)[2])
    }

    @Test
    fun theListHasEveryGroup() {
        val groups = TrackTiles.options(live, null, receiverHasG = true, receiver = "RaceBox")
        val titles = listOf("Timing", "GPS · RaceBox", "Car · OBD-II", "You · heart rate")
        assertEquals(titles, groups.map { it.title })
        val coolant = groups[2].options.first { it.id == "obd_coolant_temp" }
        assertTrue(coolant.hasData)
        assertEquals("No data", groups[0].options.first().value)
        val settings = TrackTiles.settingsOptions(receiverHasG = false, receiver = null)
        assertTrue(settings.all { g -> g.options.all { it.hasData && it.value.isEmpty() } })
        assertEquals("GPS", settings[1].title)
    }
}
