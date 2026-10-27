package com.flappedear.betterdriver.setup

import kotlinx.serialization.Serializable

/**
 * The track screen's data tiles (FBBD-262): the bottom panel of the main screen and the second
 * screen reached by a swipe. Each slot holds the id of the channel it shows, or null for an empty
 * tile. Ids are the names in [TileIds], and for car and body data the recorder's channel name
 * ("obd_coolant_temp", "heart_rate"), so a PID the app learns later needs no new id here.
 *
 * Slots are kept in order and up to [MaxMainTiles] and [MaxSecondTiles]; how many show depends on
 * the screen's layout (UI review round 31, UI-120 and UI-121), so a smaller layout shows the first
 * ones and a larger one shows the rest again. An id this build does not know is kept, so a newer
 * build's choice survives going back.
 */
@Serializable
data class TrackScreens(
    val main: List<String?> = DefaultMainTiles,
    val second: List<String?> = DefaultSecondTiles,
) {
    /** The ids of [page], [MaxMainTiles] or [MaxSecondTiles] slots long, empty slots as null. */
    fun tiles(page: TrackPage): List<String?> {
        val list = if (page == TrackPage.MAIN) main else second
        return List(page.slots) { list.getOrNull(it) }
    }

    /**
     * Sets [slot] of [page] to show [id], or to empty for null. A slot out of range changes nothing.
     * Slots past the layout's (the second row of 1.4.0's ten tiles) are kept.
     */
    fun choose(page: TrackPage, slot: Int, id: String?): TrackScreens {
        if (slot !in 0 until page.slots) return this
        val list = if (page == TrackPage.MAIN) main else second
        val next = List(maxOf(page.slots, list.size)) { list.getOrNull(it) }.toMutableList()
        next[slot] = id?.takeIf { s -> s.isNotBlank() }
        return if (page == TrackPage.MAIN) copy(main = next) else copy(second = next)
    }

    companion object {
        /**
         * The screens for a setup saved before FBBD-262: its chosen tiles first, in their order,
         * then the default tiles that are not among them, so nothing it showed is lost.
         */
        fun from(tiles: List<TrackChannel>): TrackScreens {
            val chosen = tiles.mapNotNull { it.tileId }.distinct()
            return TrackScreens(main = (chosen + DefaultMainTiles.filterNot { it in chosen }).take(MaxMainTiles))
        }
    }
}

/** The track screen's two screens. */
enum class TrackPage(val slots: Int) {
    /** The bottom panel under the delta and lap times. */
    MAIN(MaxMainTiles),

    /** The screen a swipe shows. */
    SECOND(MaxSecondTiles),
}

/** Most tiles the main screen's layouts show: one row of six (UI-120 A, "6 in one row"). */
const val MaxMainTiles = 6

/** Most tiles the second screen's layouts show: three rows of four (UI-121 B). */
const val MaxSecondTiles = 12

/** Ids of the channels that are neither car nor body data. */
object TileIds {
    const val SPEED = "speed"
    const val G = "g"
    const val SATELLITES = "satellites"
    const val DELTA = "delta"
    const val LAP_TIME = "lap_time"
    const val LAST_LAP = "last_lap"
    const val BEST_LAP = "best_lap"
    const val PREDICTED_LAP = "predicted_lap"
    const val LAP_NUMBER = "lap_number"

    /** The heart-rate monitor, by the recorder's channel name. */
    const val HEART_RATE = "heart_rate"

    /** The recorder names OBD-II channels "obd_<PID id>". */
    const val OBD_PREFIX = "obd_"

    fun obd(pidId: String) = OBD_PREFIX + pidId
}

/** The id a tile chosen before FBBD-262 shows; LAP is not offered any more (UI-82). */
val TrackChannel.tileId: String?
    get() = when (this) {
        TrackChannel.SPEED -> TileIds.SPEED
        TrackChannel.G -> TileIds.G
        TrackChannel.LAP -> null
    }

/**
 * A new install's main screen, UI review round 31 UI-125 option B ("as today"): speed and G, the
 * other slots empty for the driver to fill. G is left out with a receiver that cannot give it (UI-126 B).
 */
val DefaultMainTiles: List<String?> = listOf(TileIds.SPEED, TileIds.G)

/** A new install's second screen (UI-125 B): empty until the driver fills it. */
val DefaultSecondTiles: List<String?> = emptyList()
