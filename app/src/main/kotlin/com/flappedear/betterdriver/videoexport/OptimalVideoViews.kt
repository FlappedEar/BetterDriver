package com.flappedear.betterdriver.videoexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.optimal.CompositeLapVideoAbout
import com.flappedear.betterdriver.analysis.optimal.CompositeLapVideoTitle
import com.flappedear.betterdriver.analysis.optimal.CompositeOptions
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.OptimalOption
import com.flappedear.betterdriver.analysis.optimal.OptimalPartRow
import com.flappedear.betterdriver.analysis.optimal.PartOnVideo
import com.flappedear.betterdriver.analysis.optimal.RealisticOptimalVideoLabel
import com.flappedear.betterdriver.analysis.optimal.missingText
import com.flappedear.betterdriver.analysis.optimal.optimalOptionText
import com.flappedear.betterdriver.analysis.optimal.optimalPartText
import com.flappedear.betterdriver.analysis.optimal.optimalVideoNote
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.VideoExportLine

/** Why no video can be made right now, whatever the session: a recording, or another export. */
internal fun videoBlocked(recording: Boolean, busy: Boolean): String? = when {
    recording -> "Not while a session is recording"
    busy -> "Another video is being made"
    else -> null
}

/**
 * "Realistic optimal video" under the lap times of the lap analysis (FBBD-163, FBBD-178): the
 * button, with what the video will leave out or why it cannot be made beside it, and under them
 * the export's progress with Cancel, then how it ended. [options] is null while the parts are
 * looked for on video.
 */
@Composable
internal fun OptimalVideoAction(
    options: CompositeOptions?,
    state: TransferState?,
    rendering: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(onClick = onOpen, enabled = options?.enabled == true && !rendering) {
            Text(RealisticOptimalVideoLabel)
        }
        DimText(optimalVideoNote(options), Modifier.weight(1f))
    }
    VideoExportLine(state, rendering, onCancel)
}

/**
 * What a composite lap's video is made of, before it is made (FBBD-163, FBBD-178): the realistic
 * optimal or the raw theoretical best (the realistic optimal first), then every part of the chosen
 * one in track order with the lap it comes from and its time, those without video marked and
 * summed up, as they are skipped. [blocked] says why no video can be made right now; [onExport]
 * gets the kind chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptimalVideoSheet(
    options: CompositeOptions,
    blocked: String?,
    onExport: (OptimalKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrackTheme.colors
    var kind by rememberSaveable(options) { mutableStateOf(options.preselected.kind) }
    val option = options.of(kind)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                CompositeLapVideoTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            for (o in listOf(options.realistic, options.raw)) {
                KindRow(o, selected = o.kind == kind) { kind = o.kind }
            }
            DimText(CompositeLapVideoAbout)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = PartsMaxHeight)) {
                items(option.parts) { PartLine(it) }
            }
            missingText(option)?.let { DimText(it, color = c.warning) }
            (option.unavailable ?: blocked)?.let { DimText(it, color = c.loss) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                FilledTonalButton(onClick = { onExport(kind) }, enabled = option.enabled && blocked == null) {
                    Text("Export")
                }
            }
        }
    }
}

/** "Realistic optimal · 1:39.43" over what it is made of, as a radio choice. */
@Composable
private fun KindRow(o: OptimalOption, selected: Boolean, onClick: () -> Unit) {
    val (name, note) = optimalOptionText(o)
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = o.enabled, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = o.enabled)
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (o.enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DimText(note)
        }
    }
}

/** "Corner 3   Lap 5 · 0:05.12   No video". */
@Composable
private fun PartLine(p: OptimalPartRow) {
    val c = TrackTheme.colors
    val (detail, video) = optimalPartText(p)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            p.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (p.video == PartOnVideo.None) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            DimText(detail)
            video?.let { DimText(it, color = if (p.video == PartOnVideo.None) c.loss else c.warning) }
        }
    }
}

private val PartsMaxHeight = 320.dp
