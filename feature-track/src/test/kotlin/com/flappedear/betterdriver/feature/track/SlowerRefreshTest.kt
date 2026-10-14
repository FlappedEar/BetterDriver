package com.flappedear.betterdriver.feature.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The display mode the track screen asks for to draw fewer frames (FBBD-46). */
class SlowerRefreshTest {
    private val pixel6At90 = RefreshMode(id = 2, width = 1080, height = 2400, hz = 90f)
    private val pixel6At60 = RefreshMode(id = 1, width = 1080, height = 2400, hz = 60f)

    @Test
    fun aPixel6At90HzDropsTo60() {
        assertEquals(pixel6At60, slowerRefresh(listOf(pixel6At60, pixel6At90), pixel6At90))
    }

    @Test
    fun aScreenAlreadyAt60StaysAsItIs() {
        assertNull(slowerRefresh(listOf(pixel6At60, pixel6At90), pixel6At60))
    }

    @Test
    fun neverBelow60HzNorAtAnotherSize() {
        val modes = listOf(
            RefreshMode(id = 3, width = 1080, height = 2400, hz = 30f),
            RefreshMode(id = 4, width = 720, height = 1600, hz = 60f),
            RefreshMode(id = 5, width = 1080, height = 2400, hz = 120f),
            RefreshMode(id = 6, width = 1080, height = 2400, hz = 59.94f),
        )
        val at120 = modes[2]
        assertEquals(6, slowerRefresh(modes, at120)?.id)
    }
}
