package com.flappedear.betterdriver.shell

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
import androidx.compose.material3.LinearProgressIndicator
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
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.videoexport.VideoExportOptions
import com.flappedear.betterdriver.videoexport.VideoPart
import com.flappedear.betterdriver.videoexport.WholeVideo
import com.flappedear.betterdriver.videoexport.lapOptionText
import com.flappedear.betterdriver.videoexport.optimalOptionText
import com.flappedear.betterdriver.videoexport.videoPartOf
import com.flappedear.betterdriver.videoexport.wholeVideoText

/** Video with data on the Sessions screen (FBBD-152): progress and results by session id, and the actions. */
data class SessionVideoExport(
    val states: Map<String, TransferState> = emptyMap(),
    /** The session whose video is being made, if any: one at a time. */
    val running: String? = null,
    /** A session is recording: no video is made meanwhile. */
    val recording: Boolean = false,
    val onExport: (SessionSummary) -> Unit = {},
    val onCancel: (SessionSummary) -> Unit = {},
    /** Opens the session's whole video with the data overlay in the app (FBBD-341). */
    val onWatch: (SessionSummary) -> Unit = {},
) {
    internal fun menuItem(onClick: () -> Unit) = DataVideoItem(
        enabled = !recording && running == null,
        detail = dataVideoDetail(recording, busy = running != null),
        onClick = onClick,
    )
}

/** The card's Video with data line: a bar with Cancel while it renders, then how it ended. */
@Composable
internal fun VideoExportLine(state: TransferState?, rendering: Boolean, onCancel: () -> Unit) {
    if (state == null) return
    TransferLine(state)
    if (rendering) TextButton(onClick = onCancel) { Text("Cancel video", color = TrackTheme.colors.loss) }
}

/**
 * What Video with data exports (FBBD-152): the whole session, a composite lap (FBBD-163, FBBD-178) or one lap,
 * each lap with its time and the best lap chosen to start with; laps the cameras missed cannot be
 * chosen. A bottom sheet, so a long session's laps scroll. [loading] while the session is read;
 * [options] is then null if its video could not be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoExportSheet(
    title: String,
    loading: Boolean,
    options: VideoExportOptions?,
    onExport: (VideoPart) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by rememberSaveable(options) { mutableStateOf(options?.preselected) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Video with data",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            DimText(
                "$title. Speed, lap time, delta to your best lap, G and the track map are drawn into the video " +
                    "on this phone. It takes a while; you can leave the app meanwhile.",
            )
            when {
                loading -> {
                    DimText("Reading laps and video…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                options == null -> DimText("This session's video could not be read.", color = TrackTheme.colors.loss)
                else -> PartList(options, picked) { picked = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                FilledTonalButton(
                    onClick = { picked?.let(onExport) },
                    enabled = options != null && picked != null,
                ) { Text("Export") }
            }
        }
    }
}

@Composable
private fun PartList(options: VideoExportOptions, picked: VideoPart?, onPick: (VideoPart) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = ListMaxHeight)) {
        item {
            PartRow(
                "Whole session",
                wholeVideoText(options.wholeMicros),
                selected = picked == WholeVideo,
                enabled = options.wholeMicros > 0,
            ) { onPick(WholeVideo) }
        }
        // The composite laps, filmed (FBBD-163): the realistic optimal first, then the raw theoretical best
        // (FBBD-178). Each note lists the parts without video, or why it cannot be made.
        for (composite in listOfNotNull(options.optimal, options.rawTheoretical)) {
            val part = videoPartOf(composite.kind)
            item(key = "composite$part") {
                val (name, note) = optimalOptionText(composite)
                PartRow(name, note, picked == part, composite.enabled) { onPick(part) }
            }
        }
        items(options.laps, key = { it.number }) { lap ->
            val (name, note) = lapOptionText(lap, options.pointToPoint)
            PartRow(name, note, picked == lap.number, lap.onVideo, best = lap.best) { onPick(lap.number) }
        }
        if (options.laps.isEmpty()) {
            item { DimText("No laps: the session was not at a saved track with a start/finish line.") }
        }
    }
}

@Composable
private fun PartRow(
    name: String,
    note: String,
    selected: Boolean,
    enabled: Boolean,
    best: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val noteColor = if (best && enabled) TrackTheme.colors.best else Color.Unspecified
            if (note.isNotEmpty()) DimText(note, color = noteColor)
        }
    }
}

private val ListMaxHeight = 360.dp
