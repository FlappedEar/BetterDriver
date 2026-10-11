package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
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

/** The settings row's value (UI-64 option B): "3 focus corners". */
internal fun coachLabel(s: CoachSettings): String = when (s.verbosity) {
    Verbosity.Off -> "Off"
    Verbosity.LapTimes -> "Lap times"
    Verbosity.Focus -> "3 focus corners"
    Verbosity.Full -> "Every corner"
}

private fun seconds(ms: Int) = String.format(Locale.ROOT, "%.1f s", ms / MillisPerSecond)

/**
 * Spoken coach settings (FBBD-98, UI-64 option B), a page of its own opened from Settings › App: how
 * much it says, the audio delay of the headset (up to 2 s, so a cue still ends before the braking
 * zone), and how many laps it learns before the first corner cue. Each change is saved at once.
 * "Test voice" says a sample cue on the current audio output.
 */
@Composable
fun CoachSettingsScreen(current: CoachSettings, onChange: (CoachSettings) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Spoken coach", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShellCard { VerbosityPicker(current, onChange) }
            ShellCard { DelayAndLaps(current, onChange) }
            ShellCard { TestVoice() }
        }
    }
}

@Composable
private fun VerbosityPicker(s: CoachSettings, onChange: (CoachSettings) -> Unit) {
    Verbosity.entries.forEach { v ->
        val pick = Modifier.selectable(selected = s.verbosity == v, onClick = { onChange(s.copy(verbosity = v)) })
        Row(Modifier.fillMaxWidth().then(pick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.verbosity == v, onClick = null)
            Text(verbosityLabel(v), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The delay is saved when the slider is let go, not on every step of a drag. */
@Composable
private fun DelayAndLaps(s: CoachSettings, onChange: (CoachSettings) -> Unit) {
    var delayMs by remember(s.audioDelayMillis) { mutableIntStateOf(s.audioDelayMillis) }
    Text("Audio delay ${seconds(delayMs)}", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = delayMs / MillisPerSecond,
        onValueChange = { v ->
            val ms = (v * MillisPerSecond / DelayStepMillis).roundToInt() * DelayStepMillis
            delayMs = ms.coerceIn(0, CoachSettings.MaxAudioDelayMillis)
        },
        onValueChangeFinished = { if (delayMs != s.audioDelayMillis) onChange(s.copy(audioDelayMillis = delayMs)) },
        valueRange = 0f..CoachSettings.MaxAudioDelayMillis / MillisPerSecond,
        steps = CoachSettings.MaxAudioDelayMillis / DelayStepMillis - 1,
    )
    DimText("Bluetooth headsets lag. Cues are said this much earlier.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Learning laps", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onChange(s.copy(learningLaps = s.learningLaps - 1)) },
            enabled = s.learningLaps > 0,
        ) { Text("−") }
        Text(s.learningLaps.toString(), style = MaterialTheme.typography.bodyLarge)
        TextButton(
            onClick = { onChange(s.copy(learningLaps = s.learningLaps + 1)) },
            enabled = s.learningLaps < CoachSettings.MaxLearningLaps,
        ) { Text("+") }
    }
    DimText("The coach says only lap times until it has learned this many laps.")
}

@Composable
private fun TestVoice() {
    val context = LocalContext.current
    val voice = remember { Voice(context) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DimText("Plays “$SampleCue” on the current audio output.", Modifier.weight(1f))
        TextButton(onClick = { voice.say(SampleCue) }) { Text("Test voice") }
    }
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun CoachSettingsScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            CoachSettingsScreen(CoachSettings(), onChange = {}, onBack = {})
        }
    }
}
