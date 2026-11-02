package com.flappedear.betterdriver.timing.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        // Google's terms: its tiles are not kept on disk (FBBD-412).
        assertEquals(false, source.stored)
        assertEquals(true, TileSources.mapTiler("k").stored)
    }

    @Test
    fun googlesSatelliteComesFirstOnceItHasASession() {
        val google = TileSources.googleSatellite("k", "s")
        assertEquals(
            listOf("google-satellite", "maptiler-satellite", "osm"),
            TileSources.available("m", google).map { it.id },
        )
        assertEquals(listOf("osm"), TileSources.available().map { it.id })
    }

    @Test
    fun theSessionAndItsExpiryAreReadFromGooglesAnswer() {
        val answer = """{"session":"abc","expiry":"1762041600","tileWidth":512,"imageFormat":"jpeg"}"""
        assertEquals(GoogleSession("abc", 1_762_041_600L), GoogleMapTiles.parseSession(answer, 0))
        assertEquals(GoogleSession("abc", 186_400L), GoogleMapTiles.parseSession("""{"session":"abc"}""", 100_000))
        assertNull(GoogleMapTiles.parseSession("""{"error":{"code":403}}""", 0))
        assertNull(GoogleMapTiles.parseSession("not json", 0))
    }

    @Test
    fun theCreditNamesEachOwnerOnce() {
        assertEquals("Google Maps", GoogleMapTiles.credit(emptyList()))
        assertEquals("Google Maps", GoogleMapTiles.credit(listOf("")))
        assertEquals(
            "Google Maps · Airbus, Maxar Technologies",
            GoogleMapTiles.credit(listOf("Maxar Technologies, Airbus", "Airbus")),
        )
        assertEquals("Airbus", GoogleMapTiles.parseCopyright("""{"copyright":"Airbus","maxZoom":21}"""))
        assertNull(GoogleMapTiles.parseCopyright("{}"))
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
