package com.flappedear.betterdriver.timing.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Google's satellite tiles (FBBD-411): the addresses the app asks for. */
class GoogleMapTilesTest {
    @Test
    fun aTileAddressCarriesTheSessionAndTheKey() {
        val source = TileSources.googleSatellite("k1", "s1")
        assertEquals("google-satellite", source.id)
        assertEquals(
            "https://tile.googleapis.com/v1/2dtiles/18/144248/88303?session=s1&key=k1",
            source.url(18, 144_248, 88_303),
        )
        assertEquals(512, source.imagePx)
        assertEquals("Google Maps", source.attribution)
    }

    @Test
    fun theSessionIsForASatelliteMapIn512PxTiles() {
        assertEquals("https://tile.googleapis.com/v1/createSession?key=k1", GoogleMapTiles.createSessionUrl("k1"))
        val body = GoogleMapTiles.SESSION_REQUEST
        assertTrue("\"mapType\":\"satellite\"" in body, body)
        assertTrue("\"scale\":\"scaleFactor2x\"" in body, body)
    }

    @Test
    fun theViewportAddressNamesTheArea() {
        assertEquals(
            "https://tile.googleapis.com/tile/v1/viewport?session=s&key=k&zoom=17" +
                "&north=50.6&south=50.5&east=18.2&west=18.0",
            GoogleMapTiles.viewportUrl("k", "s", 17, 50.6, 50.5, 18.2, 18.0),
        )
    }
}
