package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackScreensTest {
    private var n = 0
    private val ids = { "id${n++}" }

    @Test
    fun `a fresh install starts with speed and G, the rest empty`() {
        val screens = Setup.fresh(ids).trackScreens
        val main = screens.tiles(TrackPage.MAIN)
        assertEquals(listOf("speed", "g") + List(MaxMainTiles - 2) { null }, main)
        assertEquals(List(MaxSecondTiles) { null }, screens.tiles(TrackPage.SECOND))
    }

    @Test
    fun `a setup saved before the data panel keeps its tiles first`() {
        val old = Setup().chooseTiles(listOf(TrackChannel.G, TrackChannel.SPEED))
        val main = old.trackScreens.tiles(TrackPage.MAIN)
        assertEquals(listOf("g", "speed", null), main.take(3))
        assertEquals(listOf("speed", "g"), Setup().chooseTiles(listOf(TrackChannel.SPEED)).trackScreens.main)
        assertEquals(MaxMainTiles, main.size)
    }

    @Test
    fun `choosing a tile changes only that slot, and null empties it`() {
        val s = Setup().chooseTile(TrackPage.MAIN, 2, "obd_fuel_level").chooseTile(TrackPage.SECOND, 11, null)
        assertEquals("obd_fuel_level", s.trackScreens.tiles(TrackPage.MAIN)[2])
        assertEquals("speed", s.trackScreens.tiles(TrackPage.MAIN)[0])
        assertNull(s.trackScreens.tiles(TrackPage.SECOND)[11])
        assertEquals(s, s.chooseTile(TrackPage.MAIN, MaxMainTiles, "speed"), "a slot out of range changes nothing")
    }

    @Test
    fun `each car keeps its own tiles, starting from the shared ones`() {
        val shared = Setup.fresh(ids).chooseTile(TrackPage.MAIN, 2, "heart_rate")
            .addCar("FR-S", newId = ids).addCar("Corolla", newId = ids)
        val (frs, corolla) = shared.cars.map { it.id }
        assertEquals("heart_rate", shared.trackScreensFor(corolla).tiles(TrackPage.MAIN)[2], "no own layout yet")
        val s = shared.chooseTile(TrackPage.MAIN, 3, "obd_steering_angle", frs)
        assertEquals("obd_steering_angle", s.trackScreensFor(frs).tiles(TrackPage.MAIN)[3])
        assertEquals("heart_rate", s.trackScreensFor(frs).tiles(TrackPage.MAIN)[2], "the shared tiles carried over")
        assertNull(s.trackScreensFor(corolla).tiles(TrackPage.MAIN)[3])
        assertEquals(shared.session.screens, s.session.screens, "the shared tiles stay as they were")
        assertEquals(s.trackScreensFor(frs), s.trackScreens, "the next session uses its car's tiles")
        assertEquals(s.trackScreensFor(corolla), s.chooseCar(corolla).trackScreens)
        assertEquals(TrackScreens(), s.resetTiles(frs).trackScreensFor(frs))
        assertEquals(s.trackScreensFor(corolla), s.resetTiles(frs).trackScreensFor(corolla))
    }

    @Test
    fun `reset puts the default tiles back on both screens`() {
        val s = Setup.fresh(ids).chooseTile(TrackPage.MAIN, 0, "obd_rpm").chooseTile(TrackPage.SECOND, 3, "heart_rate")
        val reset = s.resetTiles()
        assertEquals(TrackScreens(), reset.trackScreens)
        assertEquals(s.equipment, reset.equipment, "only the tiles change")
    }

    @Test
    fun `tiles past the six shown, saved by 1_4_0's ten, are kept when a slot changes`() {
        val ten = TrackScreens(main = List(10) { "c$it" })
        assertEquals(List(MaxMainTiles) { "c$it" }, ten.tiles(TrackPage.MAIN))
        val next = ten.choose(TrackPage.MAIN, 1, "speed")
        assertEquals(listOf("c0", "speed") + (2 until 10).map { "c$it" }, next.main)
    }

    @Test
    fun `short or unknown lists read as full screens and survive a save`() {
        val screens = TrackScreens(main = listOf("speed", "some_future_channel"), second = emptyList())
        assertEquals(
            listOf("speed", "some_future_channel") + List(MaxMainTiles - 2) { null },
            screens.tiles(TrackPage.MAIN),
        )
        val s = Setup().copy(session = SessionChoice(screens = screens))
        assertEquals(s, SetupJson.decode(SetupJson.encode(s)))
    }
}
