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
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
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
 * The two composite laps of a session as the lap analysis offers them (FBBD-178): the [realistic]
 * optimal, filmed by default, and the [raw] theoretical best.
 */
data class CompositeOptions(val realistic: OptimalOption, val raw: OptimalOption) {
    /** Some composite lap can be filmed. */
    val enabled: Boolean get() = realistic.enabled || raw.enabled

    /** The one the sheet starts on: the realistic optimal, unless only the raw theoretical best can be made. */
    val preselected: OptimalOption get() = if (realistic.enabled || !raw.enabled) realistic else raw

    fun of(kind: OptimalKind): OptimalOption = if (kind == OptimalKind.RealisticOptimal) realistic else raw
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
        val o = options?.realistic
        val note = when {
            o == null -> "Finding the parts on video…"
            !o.enabled -> o.unavailable.orEmpty()
            else -> missingText(o) ?: "Every part on video, from ${lapsText(o.laps)}"
        }
        DimText(note, Modifier.weight(1f))
    }
    VideoExportLine(state, rendering, onCancel)
}

/** The lap analysis's button for the composite video: the realistic optimal is the default. */
const val RealisticOptimalVideoLabel = "Realistic optimal video"

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
                "Composite lap video",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            for (o in listOf(options.realistic, options.raw)) {
                KindRow(o, selected = o.kind == kind) { kind = o.kind }
            }
            DimText(
                "Each corner and straight from the lap shown, joined into one video. The overlay shows the " +
                    "lap each part comes from, the running time, the gain on your best actual lap, speed, G " +
                    "and the track map. It takes a while; you can leave the app meanwhile.",
            )
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
