package com.flappedear.betterdriver.shell

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.camera.supportedPhoneModes
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize

/** What a mode costs or gives, under its name in the dialog. */
private fun modeNote(mode: VideoMode, recommended: Boolean): String {
    val size = when (mode.size) {
        VideoSize.UHD -> "Sharpest; most heat and storage"
        VideoSize.QHD -> "Between 4K and 1080p"
        VideoSize.FHD -> "A quarter of 4K's pixels"
        VideoSize.HD -> "Smallest files"
    }
    val rate = if (mode.fps < HighFps) " · less smooth at speed" else ""
    return (if (recommended) "Recommended · " else "") + size + rate
}

private const val HighFps = 60

/**
 * The modes a camera can be set to (FBBD-88). For the phone, only what its back camera reports,
 * read once when shown; null while reading. For a GoPro, the modes its model is known to take
 * (GoProModel, from the Open GoPro spec).
 */
@Composable
internal fun rememberVideoModes(item: Equipment): List<VideoMode>? {
    if (item.model != EquipmentModel.PHONE_CAMERA) return item.videoModes
    val context = LocalContext.current
    var modes by remember { mutableStateOf<List<VideoMode>?>(null) }
    DisposableEffect(Unit) {
        var live = true
        phoneModes(context) { if (live) modes = it }
        onDispose { live = false }
    }
    return modes
}

/** Reads the phone's modes with CameraX; no camera is opened. Any failure gives an empty list. */
@Suppress("TooGenericExceptionCaught") // CameraX reports a missing or busy camera service as exceptions.
private fun phoneModes(context: Context, onModes: (List<VideoMode>) -> Unit) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val modes = try {
            supportedPhoneModes(future.get(), EquipmentModel.PHONE_CAMERA.videoModes)
        } catch (e: Exception) {
            DebugLog.w("VideoModes", "could not read the phone's video modes", e)
            emptyList()
        }
        onModes(modes)
    }, ContextCompat.getMainExecutor(context))
}

/** A camera's Video row value, such as "1080p · 60". */
internal fun videoLabel(item: Equipment): String = item.videoMode?.label.orEmpty()

/**
 * Choose a camera's video mode (UI-39): one list, best first, with the recommended mode marked.
 * [modes] is null while the phone is being read.
 */
@Composable
internal fun VideoModeDialog(
    item: Equipment,
    modes: List<VideoMode>?,
    onChoose: (VideoMode) -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember { mutableStateOf(item.videoMode) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Video · ${item.name}") },
        text = {
            Column {
                when {
                    modes == null -> DimText("Checking the camera…")
                    modes.isEmpty() -> DimText("This phone did not report any video modes.")
                    else -> modes.forEach { m ->
                        ModeRow(m, picked == m, m == item.defaultVideo) { picked = m }
                    }
                }
                DimText(
                    if (item.model == EquipmentModel.PHONE_CAMERA) {
                        "Only modes this phone supports. If the phone gets hot, video drops resolution first and " +
                            "keeps this frame rate as long as it can; data recording never stops."
                    } else {
                        "Set on the GoPro when the session starts. If it refuses, it records with its own setting."
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked != null && !modes.isNullOrEmpty(),
                onClick = {
                    picked?.let(onChoose)
                    onClose()
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ModeRow(mode: VideoMode, selected: Boolean, recommended: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text("${mode.size.label} · ${mode.fps} fps", style = MaterialTheme.typography.bodyLarge)
            DimText(modeNote(mode, recommended))
        }
    }
}
