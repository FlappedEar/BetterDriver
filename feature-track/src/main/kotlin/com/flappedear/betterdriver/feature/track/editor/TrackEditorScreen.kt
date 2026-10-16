package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.timing.edit.line
import com.flappedear.betterdriver.timing.edit.lineRefs
import com.flappedear.betterdriver.timing.live.LapBook
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.map.TileSources
import com.flappedear.betterdriver.timing.track.SegmentEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Wide enough for the map and the lap table side by side (UI-25). */
private val TabletWidth = 720.dp
private val PanelWidth = 380.dp
private const val RetimeDelayMs = 150L
private const val FitMarginPx = 48f

/**
 * Track editor (FBBD-56). Phone: map on top, a sheet listing every line (UI-23 option A). Tablet:
 * map with a side panel of lines and the newest session's laps, re-timed as lines move (UI-25
 * option A). A line is placed from the recorded lap by default (UI-24 option A), on a satellite
 * background where one is available (UI-26 option A).
 *
 * [sessions] are the recorded sessions at this track, newest first. [onSave] stores the track;
 * [onBack] leaves (the caller asks about unsaved changes). [onShare], when given, sends the saved
 * track to the other device (FBBD-80, UI-56 option B); it is off while there are unsaved changes,
 * so what is sent is what is shown. [onDelete], when given, asks to delete the track (FBBD-157);
 * the caller confirms first.
 *
 * Under the lines are the track's corners and straights (FBBD-179). [onCorrect], when given, stores
 * a correction the moment it is made (the caller reports back to [TrackEditorState.correctionStored]);
 * without it they are only shown.
 */
@Composable
fun TrackEditorScreen(
    state: TrackEditorState,
    sessions: List<EditorSession>,
    tiles: TileLoader,
    backIcon: ImageVector,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCorrect: ((SegmentEdit) -> Unit)? = null,
) {
    var books by remember { mutableStateOf<List<LapBook>>(emptyList()) }
    LaunchedEffect(state.track, sessions) {
        val track = state.track
        if (!track.timeable) {
            books = emptyList()
            return@LaunchedEffect
        }
        delay(RetimeDelayMs) // Dragging a line end changes the track many times a second.
        books = withContext(Dispatchers.Default) { sessions.map { timeSession(track, it.fixes.asSequence()) } }
    }
    val back: () -> Unit = when {
        state.selected != null -> state::cancel
        state.segmentId != null -> ({ state.selectSegment(null) })
        else -> onBack
    }
    SideEffect { state.correctable = onCorrect != null }
    val map: @Composable (Modifier) -> Unit = { m -> MapPane(state, tiles, m, onCorrect) }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxWidth >= TabletWidth) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(PanelWidth).fillMaxHeight().padding(horizontal = 16.dp)) {
                    EditorBar(state, backIcon, back, onSave, onShare, onDelete)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Panel(state, onCorrect)
                        SessionLaps(sessions.firstOrNull()?.title, books.firstOrNull(), state.track)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                EditorBar(state, backIcon, back, onSave, onShare, onDelete, Modifier.padding(horizontal = 4.dp))
                map(Modifier.weight(1f).fillMaxWidth())
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Panel(state, onCorrect)
                    if (state.selected == null && state.segmentId == null) {
                        RetimeNote(sessions.size, books.sumOf { b -> b.laps.size }, state.track.pointToPoint)
                    }
                }
            }
        }
    }
}

/**
 * What the panel shows: the line being placed, the corner being corrected (FBBD-179), or the lines
 * and the corners.
 */
@Composable
private fun ColumnScope.Panel(state: TrackEditorState, onCorrect: ((SegmentEdit) -> Unit)?) {
    when {
        state.selected != null -> PlacingControls(state)
        state.segmentId != null && onCorrect != null -> SegmentControls(state, onCorrect)
        else -> {
            LineList(state)
            CornerList(state, onCorrect)
        }
    }
}

/** The map, fitted to the recorded lap (or the start/finish line) when it first gets its size. */
@Composable
private fun MapPane(
    state: TrackEditorState,
    tiles: TileLoader,
    modifier: Modifier,
    onCorrect: ((SegmentEdit) -> Unit)?,
) {
    val sources = remember(tiles.mapTilerKey) { TileSources.available(tiles.mapTilerKey) }
    // The best background unless another was picked; a new key brings its satellite map in first.
    var picked by remember(tiles.mapTilerKey) { mutableStateOf<String?>(null) }
    val source = sources.firstOrNull { it.id == picked } ?: sources.first()
    val camera = remember { MapCamera(state.track.center, zoom = 16.0) }
    var fitted by remember { mutableStateOf(false) }
    Box(
        modifier.onSizeChanged { size ->
            if (!fitted && size.width > 0) {
                val points = state.path?.points
                    ?: listOfNotNull(state.track.startFinish?.left, state.track.startFinish?.right)
                camera.fit(points, Size(size.width.toFloat(), size.height.toFloat()), FitMarginPx)
                fitted = true
            }
        },
    ) {
        EditorMap(state, camera, source, tiles, Modifier.fillMaxSize(), onCorrect)
        if (state.selected != null) PlacingHint(state, Modifier.align(Alignment.TopCenter))
        // Above the attribution line at the map's foot.
        MapPicker(
            sources,
            source,
            tiles,
            onPick = { picked = it.id },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun EditorBar(
    state: TrackEditorState,
    backIcon: ImageVector,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(backIcon, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            state.selected?.label(state.track.pointToPoint) ?: state.selectedSegment?.name ?: state.track.displayName,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.selected != null) {
            Button(onClick = state::done, enabled = state.selectedLine != null) { Text("Done") }
        } else if (state.segmentId != null) {
            Button(onClick = { state.selectSegment(null) }, enabled = !state.correcting) { Text("Done") }
        } else {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete", color = TrackTheme.colors.loss) }
            }
            if (onShare != null) TextButton(onClick = onShare, enabled = !state.changed) { Text("Share") }
            Button(onClick = onSave, enabled = state.changed) { Text("Save") }
        }
    }
}

@Composable
private fun PlacingHint(state: TrackEditorState, modifier: Modifier = Modifier) {
    val text = when {
        state.method == PlaceMethod.FromLap && state.selectedLine == null ->
            "Tap your lap (blue) where the line goes. It is placed square to the way you drove, 30 m wide."
        state.method == PlaceMethod.FromLap -> "Drag the ends to the track edges. Tap the lap again to move the line."
        state.firstTap == null -> "Tap one edge of the track."
        else -> "Now tap the other edge."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFEEF2F4),
        modifier = modifier
            .padding(12.dp)
            .background(Color(0xCC0B0F12), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ColumnScope.PlacingControls(state: TrackEditorState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = state.method == PlaceMethod.FromLap,
            onClick = { state.method = PlaceMethod.FromLap },
            enabled = state.path != null,
            label = { Text("From your lap") },
        )
        FilterChip(
            selected = state.method == PlaceMethod.TwoTaps,
            onClick = { state.method = PlaceMethod.TwoTaps },
            label = { Text("Two taps") },
        )
    }
    if (state.path == null) {
        DimLine("No session here yet, so lines are placed with two taps.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = state::flip, enabled = state.selectedLine != null) { Text("Flip direction") }
        TextButton(onClick = state::remove) {
            Text("Remove line", color = TrackTheme.colors.loss)
        }
    }
    DimLine("The arrow shows the way you drive across the line. Crossings the other way never count.")
}

@Composable
private fun LineList(state: TrackEditorState) {
    TrackHeader(state)
    val p2p = state.track.pointToPoint
    val refs = state.track.lineRefs()
    refs.forEachIndexed { i, ref ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().clickable { state.select(ref) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LineSwatch(ref)
            Text(ref.label(p2p), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (state.track.line(ref) != null) {
                Text("✓", color = TrackTheme.colors.gain, fontWeight = FontWeight.Bold)
            } else {
                DimLine("Not set")
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text(
        "+ Add sector line",
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.run { if (state.canAddSector) primary else onSurfaceVariant },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.canAddSector) { state.addSector() }
            .padding(vertical = 12.dp),
    )
}

