package com.flappedear.betterdriver.timing.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daily cap on Google tiles (FBBD-412), so the free tier is never exceeded. */
class GoogleTileBudgetTest {
    private val day = 20_394L

    @Test
    fun tilesAreCountedPerUtcDayAndTheCountStartsAgainTheNextDay() {
        assertEquals(GoogleTileDay(day, 1), GoogleTileBudget.take(null, day))
        assertEquals(GoogleTileDay(day, 8), GoogleTileBudget.take(GoogleTileDay(day, 7), day))
        assertEquals(GoogleTileDay(day + 1, 1), GoogleTileBudget.take(GoogleTileDay(day, 2_500), day + 1))
        assertEquals(day, GoogleTileBudget.today(day * 86_400_000L + 86_399_999L))
    }

    @Test
    fun noTileIsFetchedOnceTheDaysCapIsUsed() {
        val last = GoogleTileBudget.take(GoogleTileDay(day, GoogleTileBudget.DAILY_LIMIT - 1), day)
        assertEquals(GoogleTileDay(day, GoogleTileBudget.DAILY_LIMIT), last)
        assertTrue(GoogleTileBudget.spent(last, day))
        assertNull(GoogleTileBudget.take(last, day))
        assertFalse(GoogleTileBudget.spent(last, day + 1))
        assertFalse(GoogleTileBudget.spent(null, day))
    }

    @Test
    fun theCapStaysUnderGooglesFreeMonth() {
        assertTrue(GoogleTileBudget.DAILY_LIMIT * 31 < 100_000)
    }

    @Test
    fun theCountIsKeptAsTextAndBadTextIsNoCount() {
        val kept = GoogleTileDay(day, 42)
        assertEquals(kept, GoogleTileBudget.read(GoogleTileBudget.write(kept)))
        assertNull(GoogleTileBudget.read(null))
        assertNull(GoogleTileBudget.read("garbage"))
        assertNull(GoogleTileBudget.read("1 -3"))
    }
}
