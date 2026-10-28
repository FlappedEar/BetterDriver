package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.component.EmptyTile
import com.flappedear.betterdriver.core.ui.component.ValueTile
import com.flappedear.betterdriver.core.ui.component.tileValueSize

/** What a tile shows while its channel has no value now. */
const val NoTileValue = "–"

/**
 * Rows of tiles (FBBD-262), [columns] slots a row, slot by slot from [tiles]; null is an empty slot,
 * drawn as a dashed outline (UI-125 B). A tile whose channel has no data in this session is left
 * out and the others in its row widen (UI-126 B); a row left with nothing collapses and the other
 * rows take its height. While [editing] (the layout in Settings, FBBD-345) every tile is drawn, so
 * each can be tapped for [onTap], the ones without data as a dash. [height] is the panel's; its
 * tiles' values are sized once for it, up to [maxValueSize].
 */
@Composable
internal fun TilePanel(
    tiles: List<TrackTile?>,
    columns: Int,
    rows: Int,
    height: Dp,
    maxValueSize: Dp,
    tag: (Int) -> String,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onTap: ((Int) -> Unit)? = null,
) {
    val shown = shownRows(tiles, columns, rows, editing)
    if (shown.isEmpty()) return
    val rowHeight = (height - PanelGap * (shown.size - 1)) / shown.size
    val valueSize = tileValueSize(rowHeight, maxValueSize, TileVerticalPadding)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PanelGap)) {
        shown.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(PanelGap)) {
                row.forEach { slot ->
                    TileSlot(
                        tile = tiles[slot],
                        valueSize = valueSize,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag(tag(slot)),
                        onTap = onTap?.let { tap -> { tap(slot) } },
                    )
                }
            }
        }
    }
}

/** The slots each row shows, rows with none left out. */
internal fun shownRows(tiles: List<TrackTile?>, columns: Int, rows: Int, editing: Boolean): List<List<Int>> =
    (0 until rows).mapNotNull { r ->
        (r * columns until minOf((r + 1) * columns, tiles.size))
            .filter { tiles[it]?.hasData != false || editing }
            .takeIf { it.isNotEmpty() }
    }

@Composable
private fun TileSlot(tile: TrackTile?, valueSize: Dp, modifier: Modifier, onTap: (() -> Unit)?) {
    val tapped = if (onTap == null) modifier else modifier.clickable(onClick = onTap)
    if (tile == null) {
        EmptyTile(tapped)
    } else {
        val value = if (tile.hasData) tile.value else NoTileValue
        ValueTile(
            tile.label,
            value,
            tile.unit,
            tapped,
            tile.state,
            valueSize = valueSize,
            dim = value == NoTileValue,
            verticalPadding = TileVerticalPadding,
        )
    }
}

/** Between tiles and rows, as between the track screen's blocks. */
internal val PanelGap = 6.dp

/** Less than a lone tile's 6 dp, so the value gets the most of a row of two. */
private val TileVerticalPadding = 2.dp
