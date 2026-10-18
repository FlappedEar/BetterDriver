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

/** One thing the Demo track can drive: a session on the phone, or the made-up circuit when [sessionId] is null. */
data class DemoDrive(val sessionId: String?, val label: String)

/**
 * What the Demo track receiver drives (FBBD-235, Arek picked option A): its "Drives" row on the
 * item's screen, [choices] with the demo circuit first and the sessions newest first.
 */
data class DemoDrives(
    val current: DemoDrive,
    val choices: List<DemoDrive>,
    val onChoose: (DemoDrive) -> Unit,
) {
    companion object {
        /** The made-up circuit's choice. */
        val Circuit = DemoDrive(null, "Demo circuit (made up)")
    }
}

@Composable
internal fun DemoDrivesDialog(drives: DemoDrives, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drives") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                drives.choices.forEach { d ->
                    val on = d.sessionId == drives.current.sessionId
                    val pick = Modifier.selectable(selected = on, onClick = { drives.onChoose(d) })
                    Row(Modifier.fillMaxWidth().then(pick), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = on, onClick = null)
                        Text(d.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
