package com.flappedear.betterdriver.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.timing.live.LapKind

/** Which slot the next tapped lap goes into. */
internal enum class Slot { A, B }

/** What the picker shows. */
internal class PickerState(
    val tracks: List<ReviewTrack>,
    val track: ReviewTrack,
    val a: ReviewLap?,
    val b: ReviewLap?,
    val active: Slot,
)

/** Where the picker's taps go. */
internal class PickerActions(
    val onTrack: (ReviewTrack) -> Unit,
    val onSlot: (Slot) -> Unit,
    val onLap: (ReviewLap) -> Unit,
    val onCompare: () -> Unit,
)

/**
 * Picks the two laps to compare (FBBD-92): sessions on the track, newest first, with their laps.
 * Tap a slot, then a lap; the other slot is next. Laps can come from any sessions of the track.
 */
@Composable
internal fun LapPicker(s: PickerState, on: PickerActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize().widthIn(max = MaxPickerWidth),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.tracks.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.tracks, key = { it.id }) { t ->
                        TrackChip(t.name, t.id == s.track.id) { on.onTrack(t) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlotCard(Slot.A, s.a, LapColors.a, s.active == Slot.A, { on.onSlot(Slot.A) }, Modifier.weight(1f))
                SlotCard(Slot.B, s.b, LapColors.b, s.active == Slot.B, { on.onSlot(Slot.B) }, Modifier.weight(1f))
            }
        }
        item {
            Button(
                onClick = on.onCompare,
                enabled = s.a != null && s.b != null && s.a.key != s.b.key,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Compare") }
        }
        for (session in s.track.sessions) {
            item(key = "h-${session.id}") { SessionHeader(session) }
            items(session.laps, key = { it.key.encode() }) { lap ->
                LapRow(lap, lap.number == session.bestNumber, slotOf(lap, s), { on.onLap(lap) })
            }
        }
    }
}

private val MaxPickerWidth = 720.dp

private fun slotOf(lap: ReviewLap, s: PickerState): Slot? = when (lap.key) {
    s.a?.key -> Slot.A
    s.b?.key -> Slot.B
    else -> null
}

@Composable
private fun SlotCard(
    slot: Slot,
    lap: ReviewLap?,
    color: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(2.dp, if (active) color else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text("Lap ${slot.name}", style = MaterialTheme.typography.labelMedium, color = color)
        Text(
            lap?.let(::lapName) ?: "Tap a lap",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            lap?.sessionTitle.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val scheme = MaterialTheme.colorScheme
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) scheme.onPrimary else scheme.onSurface,
        modifier = Modifier
            .background(if (selected) scheme.primary else scheme.surface, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SessionHeader(session: ReviewSession) {
    Column(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Text(session.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        if (session.detail.isNotBlank()) {
            Text(
                session.detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LapRow(lap: ReviewLap, best: Boolean, slot: Slot?, onClick: () -> Unit) {
    val colors = TrackTheme.colors
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (slot != null) {
                val c = if (slot == Slot.A) LapColors.a else LapColors.b
                Box(
                    Modifier.size(22.dp).background(c, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(slot.name, style = MaterialTheme.typography.labelSmall, color = colors.background) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("Lap ${lap.number}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        Text(
            formatLapTime(lap.timeMillis),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                best -> colors.best
                lap.kind == LapKind.Timed -> MaterialTheme.colorScheme.onSurface
                else -> dim
            },
            modifier = Modifier.weight(1f),
        )
        val notes = listOfNotNull(
            when (lap.kind) {
                LapKind.Out -> "Out lap"
                LapKind.In -> "In lap"
                LapKind.Timed -> null
            },
            if (best) "Best" else null,
            if (lap.hasVideo) "Video" else null,
        )
        Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = dim)
    }
}
