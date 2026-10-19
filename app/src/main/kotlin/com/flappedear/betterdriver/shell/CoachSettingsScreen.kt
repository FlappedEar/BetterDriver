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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.VoiceMode
import java.util.Locale
import kotlin.math.roundToInt

private const val MillisPerSecond = 1_000f
private const val DelayStepMillis = 100

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

/** The corners picked for the coach at one track (FBBD-148), as the settings page lists them. */
data class PickedCorners(val trackId: String, val trackName: String, val corners: List<String>)

/** One voice on the voice page: the engine's [name] and "Voice 3", its number within its accent. */
data class VoiceRow(val name: String, val label: String)

/**
 * Spoken coach settings (FBBD-98, UI-64 option B), a page of its own opened from Settings › App: how
 * much it says, the corners picked for it in the lap analysis at each track with a Clear for each
 * (FBBD-148; null while they are read), the headset's audio delay as measured on this page
 * ([delayNow], FBBD-49: the coach measures it itself at each session's start) and the delay used when it
 * cannot be measured (up to 2 s, so a cue still ends before the braking zone), and how many laps it
 * learns before the first corner cue. The voice (FBBD-240, UI-90 A): a Voice row ([voiceLabel], null
 * while the phone's voices are read) opening the voice page, and speed and pitch (UI-91 B). Each change
 * is saved at once. "Test voice" ([onTest]) says a sample cue on the current audio output. Advanced:
 * whether cues are said by live text-to-speech or from clips rendered at coach start (FBBD-48).
 */
@Composable
fun CoachSettingsScreen(
    current: CoachSettings,
    onChange: (CoachSettings) -> Unit,
    onBack: () -> Unit,
    picked: List<PickedCorners>? = null,
    onClearPicked: (trackId: String) -> Unit = {},
    delayNow: String? = null,
    voiceLabel: String? = null,
    onVoice: () -> Unit = {},
    onTest: () -> Unit = {},
    engines: List<EngineRow>? = null,
    defaultEngine: String? = null,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Spoken coach", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShellCard { VerbosityPicker(current, onChange) }
            picked?.let { ShellCard { Picked(it, onClearPicked) } }
            ShellCard { DelayAndLaps(current, onChange, delayNow) }
            ShellCard { VoiceAndSpeed(current, onChange, voiceLabel, onVoice, engines, defaultEngine) }
            ShellCard { TestVoice(sampleCue(current.language), onTest) }
            SectionLabel("Advanced", Modifier.padding(start = 4.dp, top = 8.dp))
            ShellCard { VoicePicker(current, onChange) }
        }
    }
}

@Composable
private fun Picked(picked: List<PickedCorners>, onClear: (String) -> Unit) {
    Text("Picked corners", style = MaterialTheme.typography.bodyLarge)
    if (picked.isEmpty()) {
        DimText("None. In a session's lap analysis, tap “Coach this next time” on a corner to pick it.")
        return
    }
    picked.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.trackName, style = MaterialTheme.typography.bodyLarge)
                DimText(p.corners.joinToString(" · "))
            }
            TextButton(onClick = { onClear(p.trackId) }) { Text("Clear") }
        }
    }
    DimText(
        "At 3 focus corners, picked corners are coached first. " +
            "The coach names corners by the lap analysis's numbers.",
    )
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
private fun DelayAndLaps(s: CoachSettings, onChange: (CoachSettings) -> Unit, delayNow: String?) {
    Text("Headset delay", style = MaterialTheme.typography.bodyLarge)
    DimText(delayNow ?: "Measuring…")
    DimText(
        "Measured on its own at the start of every session; cues are said that much earlier. " +
            "Nothing to do but connect the headset.",
    )
    var delayMs by remember(s.audioDelayMillis) { mutableIntStateOf(s.audioDelayMillis) }
    Text("If it can't be measured ${seconds(delayMs)}", style = MaterialTheme.typography.bodyLarge)
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
    DimText(
        "The coach says only lap times until it has learned this many laps. " +
            "Back out on the same day, it needs only one.",
    )
}

/** Says the sample cue [sample] in the coach's language, voice, speed and pitch, on the current audio output. */
@Composable
private fun TestVoice(sample: String, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DimText("Plays “$sample” on the current audio output.", Modifier.weight(1f))
        TextButton(onClick = onTest) { Text("Test voice") }
    }
}

@Composable
private fun VoicePicker(s: CoachSettings, onChange: (CoachSettings) -> Unit) {
    Text("Voice playback", style = MaterialTheme.typography.bodyLarge)
    VoiceMode.entries.forEach { v ->
        val pick = Modifier.selectable(selected = s.voice == v, onClick = { onChange(s.copy(voice = v)) })
        Row(Modifier.fillMaxWidth().then(pick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.voice == v, onClick = null)
            Text(if (v == VoiceMode.Live) "Live" else "Pre-rendered", style = MaterialTheme.typography.bodyLarge)
        }
    }
    DimText(
        "Pre-rendered says each word and number from clips made at the start of a coached session, so a " +
            "cue starts sooner but sounds more clipped. Until the clips are ready it uses the live voice.",
    )
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun CoachSettingsScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            CoachSettingsScreen(
                CoachSettings(),
                onChange = {},
                onBack = {},
                picked = listOf(PickedCorners("t1", "Tor Poznań", listOf("Corner 3", "Corners 5–6"))),
                delayNow = "190 ms now, on Cardo PACKTALK",
                voiceLabel = "English (UK) · Voice 3",
                engines = listOf(EngineRow("com.google.android.tts", "Speech Services by Google")),
                defaultEngine = "com.google.android.tts",
            )
        }
    }
}
