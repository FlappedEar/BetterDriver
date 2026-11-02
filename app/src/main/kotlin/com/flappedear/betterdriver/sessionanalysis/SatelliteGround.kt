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
 * The saved video keeps a MapTiler tile seen once on the phone; Google's terms allow no stored copy
 * of its photos, so a saved video over Google's map shows the drawn road ([load] gives no tile), as
 * on iOS (FBBD-411).
 */
internal class SatelliteGround(
    private val loader: TileLoader,
    private val source: TileSource,
    override val attribution: String = source.attribution,
) : PerfectLapGround {
    override val loaded: Int get() = loader.loaded

    override fun tile(z: Int, x: Int, y: Int): Bitmap? = loader.get(source, z, x, y)?.asAndroidBitmap()

    override fun load(z: Int, x: Int, y: Int): Bitmap? =
        if (source.stored) loader.load(source, z, x, y)?.asAndroidBitmap() else null
}

/** The best satellite photo [loader] has for the 3D ground, best first as in the editor; null without a key. */
internal fun satelliteGround(loader: TileLoader): SatelliteGround? {
    val source = TileSources.available(loader.mapTilerKey, loader.google)
        .firstOrNull { it.id != TileSources.OpenStreetMap.id } ?: return null
    return SatelliteGround(loader, source, loader.credit(source))
}
