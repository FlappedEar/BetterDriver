package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel

/** The cameras Arek can say a GoPro item is: Open GoPro ones for a paired camera, any for one started by hand. */
internal fun goProChoices(model: EquipmentModel): List<GoProModel> =
    if (model == EquipmentModel.GOPRO) GoProModel.OpenGoPro else GoProModel.FileImport + GoProModel.OpenGoPro

/** Which GoPro a camera is, newest first: generations, not a fixed pair of models (FBBD-105). */
@Composable
internal fun GoProModelDialog(
    choices: List<GoProModel>,
    current: GoProModel?,
    onPick: (GoProModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which GoPro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { m ->
                    val pick = Modifier.selectable(selected = m == current, onClick = { onPick(m) })
                    Row(Modifier.fillMaxWidth().then(pick), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = m == current, onClick = null)
                        Text(goProChoiceLabel(m), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun goProChoiceLabel(m: GoProModel): String = when (m) {
    GoProModel.OTHER_OPEN_GOPRO -> "Other GoPro with Open GoPro"
    GoProModel.OTHER -> "Other GoPro"
    else -> m.label
}
