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
    fun theViewportAddressNamesTheSquareOfTilesRoundTheTile() {
        // Zoom 4 has 16 × 16 tiles: any tile's square is the whole Web Mercator world.
        val url = GoogleMapTiles.viewportUrl("k", "s", 4, 9, 5)
        val start = "https://tile.googleapis.com/tile/v1/viewport?session=s&key=k&zoom=4&north=85.0511"
        assertTrue(url.startsWith(start), url)
        assertTrue("&south=-85.0511" in url && "&east=180.0" in url && "&west=-180.0" in url, url)
        assertEquals(144_240, GoogleMapTiles.viewportArea(144_248))
    }
}
