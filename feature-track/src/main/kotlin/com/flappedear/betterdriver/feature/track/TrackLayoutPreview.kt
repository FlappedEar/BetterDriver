package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme

/**
 * One track screen drawn as it looks on the phone in landscape (914 x 411 dp, a Pixel 6), scaled to
 * the width it is given: the layout in Settings › Track screen (FBBD-345, UI-304 A). [page] 0 is the
 * main screen, 1 the second. A tap on a tile calls [onTapTile] with its slot. [state] should be
 * [TrackScreenState.editable], so a tile without data in a session is still drawn and can be tapped.
 */
@Composable
fun TrackLayoutPreview(
    state: TrackScreenState,
    page: Int,
    onTapTile: (slot: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(DesignWidth / DesignHeight)) {
        val density = LocalDensity.current
        // Every size on the track screen is in dp, text too, so a smaller density draws it smaller.
        val scale = maxWidth / DesignWidth.dp
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            BetterDriverTheme(track = true) {
                TrackScreenContent(
                    state,
                    Modifier.size(DesignWidth.dp, DesignHeight.dp).clip(RoundedCornerShape(CornerDp.dp)),
                    page = page,
                    onTapTile = { _, slot -> onTapTile(slot) },
                )
            }
        }
    }
}

/**
 * A tile's list (UI-124 A) outside the track screen, in the track screen's colours: the same list
 * the 1 s hold opens, for the layout in Settings (FBBD-345).
 */
@Composable
fun TileListDialog(
    title: String,
    current: String?,
    groups: List<TileChoiceGroup>,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BetterDriverTheme(track = true) { TilePicker(title, current, groups, onChoose, onDismiss) }
}

/** The phone the track screen is designed on, in landscape (UI-10). */
private const val DesignWidth = 914f
private const val DesignHeight = 411f
private const val CornerDp = 8
