package com.flappedear.betterdriver.feature.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass

/**
 * Lap review (FBBD-24): pick two laps of a track from any sessions, then watch them side by side,
 * each with its own video, synced by track position. Opens on the lap picker with nothing picked,
 * or on [start] when another screen asks for two laps (FBBD-153); leaving a comparison goes back to
 * the picker with nothing picked. Slow work (reading sessions) happens before this screen gets
 * [load]; until then it shows progress. On an Expanded width ([layout], FBBD-40) it uses the tablet layout: videos side by
 * side, chart and map in a row.
 */
@Composable
fun ReviewScreen(
    load: ReviewLoad,
    backIcon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    start: ReviewStart? = null,
    layout: LayoutSize = LayoutSize.Phone,
) {
    Box(modifier.fillMaxSize()) {
        val wide = layout.width == WidthClass.Expanded
        when {
            load is ReviewLoad.Loading -> Message(backIcon, onBack, progress = true) {
                if (load.total > 0) "Reading sessions: ${load.done} of ${load.total}" else "Reading sessions"
            }
            load is ReviewLoad.Ready && load.tracks.isEmpty() -> Message(backIcon, onBack, progress = false) {
                "No laps to compare yet. Laps are timed at a saved track with a start/finish line."
            }
            load is ReviewLoad.Ready -> Review(load.tracks, wide, backIcon, onBack, start)
        }
    }
}

@Composable
private fun Review(
    tracks: List<ReviewTrack>,
    wide: Boolean,
    backIcon: ImageVector,
    onBack: () -> Unit,
    start: ReviewStart?,
) {
    var trackId by rememberSaveable { mutableStateOf(firstTrack(tracks, start).id) }
    val track = tracks.firstOrNull { it.id == trackId } ?: tracks.first()
    // Nothing is picked until the driver picks it, unless another screen asked for two laps (FBBD-153).
    var picks by rememberSaveable(track.id, stateSaver = PicksSaver) {
        mutableStateOf(Picks.opening(firstPair(track, start)))
    }
    val a = LapKey.decode(picks.a)?.let(track::lap)
    val b = LapKey.decode(picks.b)?.let(track::lap)
    val shown = if (a != null && b != null && a.key != b.key) a to b else null
    BackHandler(enabled = !picks.picking && shown != null) { picks = picks.leave() }
    if (!picks.picking && shown != null) {
        val c = remember(shown) { Comparison(shown.first, shown.second) }
        val at = start?.atFor(shown.first.key, shown.second.key)
        CompareView(c, wide, onChangeLaps = { picks = picks.leave() }, startFraction = at)
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar("Compare laps", backIcon, onBack)
            LapPicker(
                PickerState(tracks, track, a, b, picks.active),
                PickerActions(
                    onTrack = { trackId = it.id },
                    onSlot = { picks = picks.copy(active = it) },
                    onLap = { lap -> picks = picks.pick(lap.key) },
                    onCompare = { picks = picks.copy(picking = false) },
                ),
            )
        }
    }
}

/** [Picks] in saved state, as its four fields. */
private val PicksSaver = listSaver<Picks, Any?>(
    save = { listOf(it.a, it.b, it.active.name, it.picking) },
    restore = { Picks(it[0] as String?, it[1] as String?, Slot.valueOf(it[2] as String), it[3] as Boolean) },
)

@Composable
private fun TopBar(title: String, backIcon: ImageVector, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(backIcon, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Message(backIcon: ImageVector, onBack: () -> Unit, progress: Boolean, text: () -> String) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Compare laps", backIcon, onBack)
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (progress) CircularProgressIndicator()
                Text(
                    text(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
