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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime
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
 * "Optimal lap video" under the theoretical best of the lap analysis (FBBD-163): the button, with
 * what the video will leave out or why it cannot be made beside it, and under them the export's
 * progress with Cancel, then how it ended. [option] is null while the parts are looked for on video.
 */
@Composable
internal fun OptimalVideoAction(
    option: OptimalOption?,
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
        FilledTonalButton(onClick = onOpen, enabled = option?.enabled == true && !rendering) {
            Text("Optimal lap video")
        }
        val note = when {
            option == null -> "Finding the parts on video…"
            !option.enabled -> option.unavailable.orEmpty()
            else -> missingText(option) ?: "Every part on video, from ${lapsText(option.laps)}"
        }
        DimText(note, Modifier.weight(1f))
    }
    VideoExportLine(state, rendering, onCancel)
}

/**
 * What the optimal lap's video is made of, before it is made (FBBD-163): every part of the
 * theoretical best in track order with the lap it comes from and its time, those without video
 * marked and summed up, as they are skipped. [blocked] says why no video can be made right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptimalVideoSheet(option: OptimalOption, blocked: String?, onExport: () -> Unit, onDismiss: () -> Unit) {
    val c = TrackTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Optimal lap video",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            val total = option.totalMillis?.let { ", ${formatLapTime(it)}" }.orEmpty()
            DimText(
                "The theoretical best$total: each corner and straight from the lap that drove it fastest, " +
                    "joined into one video. The overlay shows the lap each part comes from, the running " +
                    "theoretical time, the gain on your best lap, speed, G and the track map. It takes a " +
                    "while; you can leave the app meanwhile.",
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = PartsMaxHeight)) {
                items(option.parts) { PartLine(it) }
            }
            missingText(option)?.let { DimText(it, color = c.warning) }
            (option.unavailable ?: blocked)?.let { DimText(it, color = c.loss) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                FilledTonalButton(onClick = onExport, enabled = option.enabled && blocked == null) { Text("Export") }
            }
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
