package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.timing.track.MaxSegmentLabelChars
import com.flappedear.betterdriver.timing.track.RevisionSegment
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentType
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.edited

// The track's corners and straights in the editor (FBBD-179): the list under the lines and the
// controls of the one being corrected. IdentityLayer draws them on the map.

/** Test tag of a corner or straight's row in the list: this plus its id. */
const val SegmentRowTag = "segment-"

/**
 * The corners and straights under the lines: each with its name and, once corrected, a mark; a tap
 * picks one to correct. [onCorrect] stores a correction; null when the track is not saved yet.
 */
@Composable
internal fun CornerList(state: TrackEditorState, onCorrect: ((SegmentEdit) -> Unit)?) {
    val identity = state.identity
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Corners",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        if (identity?.corrected == true && onCorrect != null) RestoreButton(state, onCorrect)
    }
    if (identity == null) {
        DimLine("Corners show here once a session at this track has been analysed.")
        return
    }
    DimLine(
        when {
            onCorrect == null -> "Save the track to correct its corners."
            identity.corrected -> "Corrected by you. Every session here is analysed with these."
            else -> "Found from your laps. Tap one here or on the map to correct it."
        },
        Modifier.padding(bottom = 4.dp),
    )
    identity.segments.forEachIndexed { i, s ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SegmentRow(s, identity.edited(s.id), enabled = onCorrect != null) { state.selectSegment(s.id) }
    }
    state.correctionNote?.let { DimLine(it, Modifier.padding(top = 6.dp)) }
}

@Composable
private fun SegmentRow(s: RevisionSegment, edited: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(SegmentRowTag + s.id).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SegmentSwatch(s.type)
        Text(
            s.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A name of the driver's own: the number it has too, so it can be found on the map.
        if (s.label != null) DimLine(s.numberedName)
        if (edited) {
            Text("Edited", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SegmentSwatch(type: SegmentType) {
    val color = if (type == SegmentType.Straight) EditorColors.Straight else EditorColors.Corner
    val height = if (type == SegmentType.Straight) 2.dp else 4.dp
    Box(Modifier.size(width = 18.dp, height = height).background(color, RoundedCornerShape(2.dp)))
}

/** Back to the corners as found from the laps, after a question. */
@Composable
private fun RestoreButton(state: TrackEditorState, onCorrect: (SegmentEdit) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { asking = true }, enabled = !state.correcting) { Text("Restore automatic") }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Restore automatic corners?") },
            text = {
                Text(
                    "Your corrections to this track's corners are undone and the corners found from your laps " +
                        "come back. Sessions keep all their data.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    state.startCorrection(SegmentEdit.RestoreAutomatic)?.let(onCorrect)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The corner or straight being corrected: what it is (corner, chicane or straight), and rename,
 * split, merge with the next one, or remove. Its ends are dragged on the map.
 */
@Composable
internal fun SegmentControls(state: TrackEditorState, onCorrect: (SegmentEdit) -> Unit) {
    val s = state.selectedSegment ?: return
    val identity = state.identity ?: return
    fun correct(edit: SegmentEdit) {
        state.startCorrection(edit)?.let(onCorrect)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (type in SegmentType.entries) {
            val edit = SegmentEdit.ChangeType(s.id, type)
            FilterChip(
                selected = s.type == type,
                onClick = { correct(edit) },
                enabled = s.type == type || state.ifApplies(edit) != null,
                label = { Text(type.label) },
            )
        }
    }
    var naming by rememberSaveable(s.id) { mutableStateOf(false) }
    if (naming) {
        // Named in place, under what it is: no window over the map.
        SegmentNameField(
            s,
            onDone = { name ->
                naming = false
                correct(SegmentEdit.Rename(s.id, name))
            },
            onCancel = { naming = false },
        )
    } else {
        SegmentActions(state, s, identity, onRename = { naming = true }, correct = ::correct)
    }
}

/** Rename, split, merge with the next one and remove [s], each on only when it applies. */
@Composable
private fun SegmentActions(
    state: TrackEditorState,
    s: RevisionSegment,
    identity: TrackRevision,
    onRename: () -> Unit,
    correct: (SegmentEdit) -> Unit,
) {
    val split = SegmentEdit.Split(s.id, middle(s, identity))
    val merge = SegmentEdit.Merge(s.id)
    val remove = SegmentEdit.Remove(s.id)
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRename, enabled = !state.correcting) { Text("Rename") }
        OutlinedButton(onClick = { correct(split) }, enabled = state.ifApplies(split) != null) { Text("Split") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { correct(merge) }, enabled = state.ifApplies(merge) != null) {
            Text("Merge with next")
        }
        val canRemove = state.ifApplies(remove) != null
        OutlinedButton(onClick = { correct(remove) }, enabled = canRemove) {
            Text("Remove", color = if (canRemove) TrackTheme.colors.loss else Color.Unspecified)
        }
    }
    DimLine(
        "Drag the round handles on the map along the track to move where it starts and ends. " +
            "Changes are kept as you make them and used for every session here.",
        Modifier.padding(top = 6.dp),
    )
    state.correctionNote?.let { DimLine(it, Modifier.padding(top = 6.dp)) }
}

private val SegmentType.label: String
    get() = when (this) {
        SegmentType.Corner -> "Corner"
        SegmentType.Chicane -> "Chicane"
        SegmentType.Straight -> "Straight"
    }

/** Where Split cuts [s]: its middle, along the track. The driver drags the new end from there. */
private fun middle(s: RevisionSegment, identity: TrackRevision): Double {
    val length = identity.lengthMeters
    val span = (s.endMeters - s.startMeters).let { if (identity.closed) it.mod(length) else it }
    return s.startMeters + span / 2
}

/** The driver's own name for [s]; empty for its number ("Corner 3"). */
@Composable
private fun SegmentNameField(s: RevisionSegment, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable(s.id) { mutableStateOf(s.label.orEmpty()) }
    val valid = name.trim().length <= MaxSegmentLabelChars
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text(s.numberedName) },
            singleLine = true,
            isError = !valid,
            modifier = Modifier.fillMaxWidth(),
        )
        DimLine("Leave it empty for “${s.numberedName}”.", Modifier.padding(top = 6.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onDone(name) }, enabled = valid) { Text("Save") }
        }
    }
}
