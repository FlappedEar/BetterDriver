package com.flappedear.betterdriver.feature.analysis

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The satellite photo under the perfect lap in 3D (FBBD-316, UI-201 B): Web Mercator map tiles
 * at [com.flappedear.betterdriver.analysis.line.SceneMapZoom]. The app gives the MapTiler tiles
 * of the track editor once a MapTiler key is entered; without one the road is drawn (UI-201 A).
 */
interface PerfectLapGround {
    /** Shown on the picture whenever a tile is, as the tiles' terms ask. */
    val attribution: String

    /** Goes up each time a tile arrives, so a picture that reads it is drawn again. */
    val loaded: Int

    /** The tile if it is at hand; otherwise null, and it is fetched for a later frame. Main thread. */
    fun tile(z: Int, x: Int, y: Int): Bitmap?

    /** The tile, read from disk or fetched first if needed; null without one. Not on the main thread: for the video. */
    fun load(z: Int, x: Int, y: Int): Bitmap?
}

/** The satellite photo the app gives the perfect lap in 3D; null draws the road instead. */
val LocalPerfectLapGround = staticCompositionLocalOf<PerfectLapGround?> { null }
