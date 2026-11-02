package com.flappedear.betterdriver.timing.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which satellite photo a saved perfect-lap video may keep (FBBD-433), the same on both apps. */
class VideoGroundTest {
    private val google = TileSources.googleSatellite("k", "s").id
    private val mapTiler = TileSources.mapTiler("m").id

    @Test
    fun onlyMapTilersPhotoGoesIntoAVideo() {
        assertEquals(mapTiler, TileSources.forVideo("m")?.id)
        assertEquals(mapTiler, TileSources.forVideo(" m ")?.id)
        assertNull(TileSources.forVideo(null))
        assertNull(TileSources.forVideo(" "))
    }

    @Test
    fun theNoteSaysWhyTheVideoIsNotThePlayersPicture() {
        assertEquals(
            "The video shows MapTiler's photo: Google's terms let its photo be shown in the app, not kept in a video.",
            TileSources.videoNote(google, mapTiler),
        )
        val road = TileSources.videoNote(TileSources.APPLE_ID, null).orEmpty()
        assertTrue(road.startsWith("The video shows the drawn road: Apple's terms"), road)
        assertTrue(road.endsWith("With a MapTiler key (track editor › Map) it shows MapTiler's photo."), road)
    }

    @Test
    fun noNoteWhenTheVideoShowsWhatThePlayerShows() {
        assertNull(TileSources.videoNote(mapTiler, mapTiler))
        assertNull(TileSources.videoNote(null, null))
        assertNull(TileSources.videoNote(TileSources.OpenStreetMap.id, null))
    }
}
