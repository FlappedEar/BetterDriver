package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.Advice
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachVoices
import com.flappedear.betterdriver.coach.Corner
import com.flappedear.betterdriver.coach.PhraseBook
import com.flappedear.betterdriver.coach.Speech
import com.flappedear.betterdriver.coach.Turn
import java.util.Locale
import kotlin.math.roundToInt

private const val Tenths = 10f
private const val SampleCorner = 2
private const val SampleAt = 100
private const val SampleMeters = 10

/** The cue the samples and Test voice say, in [language]: "Turn 2, brake at 100, 10 later". */
internal fun sampleCue(language: CoachLanguage): String =
    PhraseBook.of(language).corner(
        Corner(SampleCorner, 0.0, 0.0, 0.0, 0.0, Turn.Straight),
        Advice.BrakeLater(SampleMeters, at = SampleAt),
    )

/** A speech engine on the phone (UI-101 A): its package ([id]) and name. */
data class EngineRow(val id: String, val label: String)

/**
 * The coach's speech (UI-96 A, UI-101 A, UI-90 A, UI-91 B): the Language row (English or Polski),
 * the Speech engine row ([engines], null while they are read; picking one forgets the voice, as each
 * engine has voices of its own), the Voice row ([label], opening the voice page), and the speed and
 * pitch sliders, saved when a slider is let go.
 */
@Composable
internal fun VoiceAndSpeed(
    s: CoachSettings,
    onChange: (CoachSettings) -> Unit,
    label: String?,
    onVoice: () -> Unit,
    engines: List<EngineRow>? = null,
    defaultEngine: String? = null,
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    ListRow("Language", CoachVoices.languageName(s.language), divider = false, onClick = { dialog = LanguageDialog })
    val engine = engines?.firstOrNull { it.id == (s.speech.engine ?: defaultEngine) }
    ListRow("Speech engine", engine?.label ?: "…", onClick = { if (engines != null) dialog = EngineDialog })
    ListRow("Voice", label ?: "…", onClick = onVoice)
    DimText(
        "Any speech engine on the phone, for example SherpaTTS with Piper voices, installed from F-Droid. " +
            "The Voice list shows that engine's voices in the language above.",
    )
    SpeechSlider("Speed", s.speech.rate, Speech.MinRate..Speech.MaxRate) {
        onChange(s.copy(speech = s.speech.copy(rate = it)))
    }
    SpeechSlider("Pitch", s.speech.pitch, Speech.MinPitch..Speech.MaxPitch) {
        onChange(s.copy(speech = s.speech.copy(pitch = it)))
    }
    DimText("Speed 0.8× to 1.5×, pitch 0.7× to 1.3×.")
    when (dialog) {
        LanguageDialog -> PickDialog(
            "Language",
            CoachLanguage.entries.map { it to CoachVoices.languageName(it) },
            s.language,
            note = "The Voice list then shows only voices of this language.",
            onPick = { onChange(s.copy(language = it)) },
            onDismiss = { dialog = null },
        )
        EngineDialog -> PickDialog(
            "Speech engine",
            engines.orEmpty().map { it.id to it.label },
            engine?.id,
            note = "Each engine has voices of its own; the coach uses its voice of the chosen language.",
            onPick = { onChange(s.copy(speech = s.speech.copy(engine = it, voiceName = null))) },
            onDismiss = { dialog = null },
        )
    }
}

private const val LanguageDialog = "language"
private const val EngineDialog = "engine"

/** A dialog of [options] (value to label), [selected] marked; tapping one picks it and closes. */
@Composable
private fun <T> PickDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    note: String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, name) ->
                    val pick = Modifier.selectable(
                        selected = value == selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (value != selected) onPick(value)
                            onDismiss()
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().then(pick).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                DimText(note, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A 0.1 step slider for a speech factor, shown as "1.2×". */
@Composable
private fun SpeechSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, onSet: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(factor(v), style = MaterialTheme.typography.bodyLarge)
    }
    Slider(
        value = v,
        onValueChange = { v = ((it * Tenths).roundToInt() / Tenths).coerceIn(range) },
        onValueChangeFinished = { if (v != value) onSet(v) },
        valueRange = range,
        steps = ((range.endInclusive - range.start) * Tenths).roundToInt() - 1,
    )
}

private fun factor(f: Float) = String.format(Locale.ROOT, "%.1f×", f)
