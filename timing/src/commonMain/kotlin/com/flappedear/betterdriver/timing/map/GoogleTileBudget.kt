package com.flappedear.betterdriver.timing.map

import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine

/** Google tiles fetched on one UTC day ([epochDay], days since 1970). */
data class GoogleTileDay(val epochDay: Long, val tiles: Int)

/**
 * The daily cap on Google tiles, the same on both apps (FBBD-412). Google's Map Tiles API is free
 * up to 100,000 2D tiles a month (checked 2026-11-01); Arek does not want to pay, so each device
 * fetches at most [DAILY_LIMIT] a day and then shows the next source (MapTiler, OpenStreetMap or
 * Apple). The count is kept on the device as [write]'s text; every [LOG_EVERY] tiles, and when the
 * cap is reached, a line goes to the log, so a drive log shows the real use.
 */
object GoogleTileBudget {
    /** Under the 3,000 a day Arek sets as the quota in Google's console: 31 × 3,000 < 100,000. */
    const val DAILY_LIMIT = 2_500

    const val LOG_EVERY = 100

    private const val MillisPerDay = 86_400_000L

    /** The UTC day of [nowMillis]. */
    fun today(nowMillis: Long): Long = nowMillis.floorDiv(MillisPerDay)

    /** The count kept as [text], or null if there is none or it cannot be read. */
    fun read(text: String?): GoogleTileDay? {
        val parts = text?.trim()?.split(" ")?.takeIf { it.size == 2 }
        val day = parts?.get(0)?.toLongOrNull()
        val tiles = parts?.get(1)?.toIntOrNull()
        return if (day != null && tiles != null && tiles >= 0) GoogleTileDay(day, tiles) else null
    }

    fun write(day: GoogleTileDay): String = "${day.epochDay} ${day.tiles}"

    /** True when [day] already used [today]'s cap: Google is not asked again until the next day. */
    fun spent(day: GoogleTileDay?, today: Long): Boolean =
        day != null && day.epochDay == today && day.tiles >= DAILY_LIMIT

    /**
     * The count after one more tile [today], to keep; null when today's cap is used, and the tile
     * must not be fetched.
     */
    fun take(day: GoogleTileDay?, today: Long): GoogleTileDay? {
        if (spent(day, today)) return null
        val tiles = (if (day?.epochDay == today) day.tiles else 0) + 1
        when {
            tiles == DAILY_LIMIT -> logLine(
                CoreLog.LevelWarn,
                GoogleMapTiles.LOG_TAG,
                "Google tiles today: $tiles, the daily cap; the next source until tomorrow (UTC)",
            )
            tiles % LOG_EVERY == 0 -> logLine(CoreLog.LevelInfo, GoogleMapTiles.LOG_TAG, "Google tiles today: $tiles")
        }
        return GoogleTileDay(today, tiles)
    }
}
