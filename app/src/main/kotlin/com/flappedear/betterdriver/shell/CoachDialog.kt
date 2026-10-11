package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.Voice
import java.util.Locale
import kotlin.math.roundToInt

private const val MillisPerSecond = 1_000f
private const val DelayStepMillis = 100
private const val SampleCue = "Turn 2, brake 10 metres later"

/** What each verbosity says, for the settings row and the dialog. */
internal fun verbosityLabel(v: Verbosity) = when (v) {
    Verbosity.Off -> "Off"
    Verbosity.LapTimes -> "Lap times only"
    Verbosity.Focus -> "Lap times and the 3 corners losing most"
    Verbosity.Full -> "Lap times and every corner"
}

/** The settings row's value: "3 focus corners · 0.4 s delay". */
internal fun coachLabel(s: CoachSettings): String {
    val what = when (s.verbosity) {
        Verbosity.Off -> return "Off"
        Verbosity.LapTimes -> "Lap times"
        Verbosity.Focus -> "3 focus corners"
        Verbosity.Full -> "Every corner"
    }
    return if (s.audioDelayMillis == 0) what else "$what · ${seconds(s.audioDelayMillis)} delay"
}

private fun seconds(ms: Int) = String.format(Locale.ROOT, "%.1f s", ms / MillisPerSecond)

/**
 * Spoken coach settings (FBBD-98): how much it says, the audio delay of the headset (up to 2 s, so a
 * cue still ends before the braking zone), and how many laps it learns before the first corner cue.
 * "Test voice" says a sample cue on the current audio output.
 */
@Composable
internal fun CoachDialog(current: CoachSettings, onDone: (CoachSettings) -> Unit, onClose: () -> Unit) {
    var s by remember { mutableStateOf(current) }
    val context = LocalContext.current
    val voice = remember { Voice(context) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Spoken coach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Verbosity.entries.forEach { v ->
                    val pick = Modifier.selectable(selected = s.verbosity == v, onClick = { s = s.copy(verbosity = v) })
                    Row(Modifier.fillMaxWidth().then(pick), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.verbosity == v, onClick = null)
                        Text(verbosityLabel(v), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Audio delay ${seconds(s.audioDelayMillis)}", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = s.audioDelayMillis / MillisPerSecond,
                    onValueChange = { v ->
                        val ms = (v * MillisPerSecond / DelayStepMillis).roundToInt() * DelayStepMillis
                        s = s.copy(audioDelayMillis = ms.coerceIn(0, CoachSettings.MaxAudioDelayMillis))
                    },
                    valueRange = 0f..CoachSettings.MaxAudioDelayMillis / MillisPerSecond,
                    steps = CoachSettings.MaxAudioDelayMillis / DelayStepMillis - 1,
                )
                DimText("Bluetooth headsets lag. Cues are said this much earlier.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Learning laps", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { s = s.copy(learningLaps = s.learningLaps - 1) },
                        enabled = s.learningLaps > 0,
                    ) { Text("−") }
                    Text(s.learningLaps.toString(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(
                        onClick = { s = s.copy(learningLaps = s.learningLaps + 1) },
                        enabled = s.learningLaps < CoachSettings.MaxLearningLaps,
                    ) { Text("+") }
                }
                TextButton(onClick = { voice.say(SampleCue) }) { Text("Test voice") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(s)
                onClose()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
