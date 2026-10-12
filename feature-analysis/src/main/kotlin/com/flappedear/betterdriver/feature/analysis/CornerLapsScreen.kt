package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta

/**
 * Opens the Corner laps screen of a corner (its segment id, FBBD-153); null where nothing can show
 * it, and then the corners offer no "Every lap".
 */
internal val LocalCornerLaps = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * [content] (the analysis screen) with "Every lap" on each corner of its Corners tab (FBBD-153):
 * tapping a corner calls [open] with its segment id.
 */
@Composable
fun ProvideCornerLaps(open: (cornerId: String) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCornerLaps provides open, content = content)
}

/** Width from which the list and the player sit side by side (the tablet). */
private val WideFrom = 840.dp

/**
 * Same corner across laps (FBBD-153, as Garmin Catalyst): every counted lap's pass through one
 * corner, fastest first, with its time through the corner, braking point and minimum speed. A lap
 * with video plays just its pass, one lap at a time: under its row on a phone, beside the list on a
 * tablet. Ticking two laps and Compare opens them in the lap review ([onCompare] gets their lap
 * indexes, the faster through the corner first). [ui] is null while it is read. [topBar] is the
 * app's bar with its back button.
 */
@Composable
fun CornerLapsScreen(
    ui: CornerLapsUi?,
    onCompare: (a: Int, b: Int) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        topBar()
        if (ui == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            CornerLaps(ui, onCompare)
        }
    }
}

@Composable
private fun ColumnScope.CornerLaps(ui: CornerLapsUi, onCompare: (Int, Int) -> Unit) {
    val player = rememberCornerPlayer()
    var tickedText by rememberSaveable(ui.cornerId) { mutableStateOf("") }
    var shown by rememberSaveable(ui.cornerId) { mutableStateOf<Int?>(null) }
    val ticked = decodeTicked(tickedText)
    val actions = RowActions(
        onTick = { tickedText = encodeTicked(toggleTicked(ticked, it)) },
        onPlay = { row ->
            shown = row.lapIndex
            row.video?.let(player::play)
        },
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= WideFrom) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(ui)
                    LapList(ui, ticked, shown, actions, inline = null)
                }
                Column(Modifier.weight(PlayerShare).fillMaxHeight()) {
                    PlayerPane(ui, ui.rows.firstOrNull { it.lapIndex == shown && it.video != null }, player)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                Header(ui, width)
                LapList(ui, ticked, shown, actions, inline = player, modifier = width)
            }
        }
    }
    CompareBar(ui, ticked, onCompare)
}

/** What a lap's row does: tick it for comparing, or play its pass. */
private class RowActions(val onTick: (Int) -> Unit, val onPlay: (CornerLapRow) -> Unit)

@Composable
private fun Header(ui: CornerLapsUi, modifier: Modifier = Modifier) {
    Card(modifier) {
        Text(ui.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Dim(
            when (ui.rows.size) {
                0 -> "No counted lap went through this corner."
                1 -> "1 counted lap through this corner."
                else -> "${ui.rows.size} counted laps, fastest through the corner first."
            },
        )
        if (!ui.anyVideo && ui.rows.isNotEmpty()) Dim("No video of this corner in this session.")
    }
}

@Composable
private fun LapList(
    ui: CornerLapsUi,
    ticked: List<Int>,
    shown: Int?,
    actions: RowActions,
    inline: CornerPlayer?,
    modifier: Modifier = Modifier,
) {
    if (ui.rows.isEmpty()) return
    Card(modifier) {
        TrackLabel("Time through the corner · brakes before it · minimum speed")
        for (row in ui.rows) {
            PassRow(ui, row, row.lapIndex in ticked, row.lapIndex == shown, actions)
            if (inline != null && row.lapIndex == shown) {
                row.video?.let {
                    CornerClipView(
                        inline,
                        it,
                        Modifier.fillMaxWidth().aspectRatio(VideoAspect).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        Dim("Tick two laps to compare them in the lap review.")
    }
}

@Composable
private fun PassRow(ui: CornerLapsUi, row: CornerLapRow, ticked: Boolean, shown: Boolean, actions: RowActions) {
    val c = TrackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (shown) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(enabled = row.reviewable) { actions.onTick(row.lapIndex) }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = { actions.onTick(row.lapIndex) }, enabled = row.reviewable)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (row.best) c.best else Color.Transparent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Dim("Lap ${row.lapNumber}", Modifier.width(LapWidth))
                Text(
                    row.seconds?.let { "${seconds(it)} s" } ?: "–",
                    color = if (row.best) c.best else c.text,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(TimeWidth),
                )
                when {
                    row.best -> Dim("Best", color = c.best)
                    row.deltaSeconds != null -> Text(formatDelta(row.deltaSeconds), color = c.loss)
                }
            }
            Dim("Brakes ${brakingText(row)} · Min ${minimumText(row, ui.speedUnit)}", Modifier.padding(start = 14.dp))
        }
        if (row.video == null) {
            Dim("No video")
        } else if (shown) {
            FilledTonalButton(onClick = { actions.onPlay(row) }) { Text("Again") }
        } else {
            OutlinedButton(onClick = { actions.onPlay(row) }) { Text("Play") }
        }
    }
}

/** The tablet's player beside the list: the pass picked to play, or what to do to play one. */
@Composable
private fun PlayerPane(ui: CornerLapsUi, row: CornerLapRow?, player: CornerPlayer) {
    Card {
        val video = row?.video
        if (row == null || video == null) {
            TrackLabel(ui.name)
            Dim(if (ui.anyVideo) "Press Play on a lap to watch its pass through the corner." else "No video to play.")
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lap ${row.lapNumber}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                row.seconds?.let { "${seconds(it)} s" } ?: "–",
                color = if (row.best) TrackTheme.colors.best else TrackTheme.colors.text,
                fontWeight = FontWeight.SemiBold,
            )
        }
        CornerClipView(player, video, Modifier.fillMaxWidth().aspectRatio(VideoAspect).clip(RoundedCornerShape(8.dp)))
        Dim("From just before the braking point to the corner exit. Tap the picture to pause.")
    }
}

/** Under the list: Compare, once two laps are ticked. */
@Composable
private fun CompareBar(ui: CornerLapsUi, ticked: List<Int>, onCompare: (Int, Int) -> Unit) {
    if (ui.rows.none { it.reviewable }) return
    val pair = comparedPair(ui, ticked)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = pair?.let { (a, b) -> "Lap ${ui.row(a)?.lapNumber} against lap ${ui.row(b)?.lapNumber}" }
            ?: "Tick two laps to compare"
        Dim(text, Modifier.weight(1f))
        Button(onClick = { pair?.let { onCompare(it.first, it.second) } }, enabled = pair != null) {
            Text("Compare")
        }
    }
}

private const val VideoAspect = 16f / 9f

/** The player's share of the tablet's width, against the list's 1. */
private const val PlayerShare = 1.3f

private val LapWidth = 52.dp
private val TimeWidth = 72.dp
