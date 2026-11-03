package com.flappedear.betterdriver.sessionanalysis

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.flappedear.betterdriver.feature.analysis.PerfectLapGround
import com.flappedear.betterdriver.feature.track.editor.TileLoader
import com.flappedear.betterdriver.timing.map.TileSource
import com.flappedear.betterdriver.timing.map.TileSources

/**
 * The satellite photo under the perfect lap in 3D (FBBD-316, UI-201 B), from the same tiles and
 * keys as the track editor: Google's when its key is entered (FBBD-412), else MapTiler's
 * (FBBD-230). [attribution] is the credit when the ground is made ([TileLoader.credit]).
 *
 * A saved video keeps MapTiler's photo, the one source whose terms allow it in a video (FBBD-433,
 * [TileSources.forVideo]): over Google's photo the video takes MapTiler's when its key is entered,
 * else draws the road, as on iOS.
 */
internal class SatelliteGround(
    private val loader: TileLoader,
    private val source: TileSource,
    override val attribution: String = source.attribution,
    /** The video's source; [source] when it is the same. */
    private val videoSource: TileSource? = source.takeIf { it.stored },
) : PerfectLapGround {
    override val loaded: Int get() = loader.loaded

    override fun tile(z: Int, x: Int, y: Int): Bitmap? = loader.get(source, z, x, y)?.asAndroidBitmap()

    override fun load(z: Int, x: Int, y: Int): Bitmap? =
        if (source.stored) loader.load(source, z, x, y)?.asAndroidBitmap() else null

    override val video: PerfectLapGround? = when {
        videoSource == null -> null
        videoSource.id == source.id -> this
        else -> SatelliteGround(loader, videoSource, videoSource.attribution)
    }

    override val videoNote: String? = TileSources.videoNote(source.id, videoSource?.id)
}

/** The best satellite photo [loader] has for the 3D ground, best first as in the editor; null without a key. */
internal fun satelliteGround(loader: TileLoader): SatelliteGround? {
    val source = TileSources.available(loader.mapTilerKey, loader.google)
        .firstOrNull { it.id != TileSources.OpenStreetMap.id } ?: return null
    return SatelliteGround(loader, source, loader.credit(source), TileSources.forVideo(loader.mapTilerKey))
}
