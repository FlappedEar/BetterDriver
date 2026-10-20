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
