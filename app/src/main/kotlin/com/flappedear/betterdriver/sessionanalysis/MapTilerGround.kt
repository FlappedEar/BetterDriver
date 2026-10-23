package com.flappedear.betterdriver.sessionanalysis

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.flappedear.betterdriver.feature.analysis.PerfectLapGround
import com.flappedear.betterdriver.feature.track.editor.TileLoader
import com.flappedear.betterdriver.timing.map.TileSources

/**
 * MapTiler's satellite photo under the perfect lap in 3D (FBBD-316, UI-201 B), from the same
 * tiles and key as the track editor (FBBD-230): a tile seen once is kept on the phone.
 */
internal class MapTilerGround(private val loader: TileLoader, key: String) : PerfectLapGround {
    private val source = TileSources.mapTiler(key)

    override val attribution: String get() = source.attribution

    override val loaded: Int get() = loader.loaded

    override fun tile(z: Int, x: Int, y: Int): Bitmap? = loader.get(source, z, x, y)?.asAndroidBitmap()

    override fun load(z: Int, x: Int, y: Int): Bitmap? = loader.load(source, z, x, y)?.asAndroidBitmap()
}
