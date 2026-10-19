package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Speech
import java.util.Locale
import kotlin.math.roundToInt

private const val Tenths = 10f

/** The voice row and the speed and pitch sliders (UI-90 A, UI-91 B), saved when a slider is let go. */
@Composable
internal fun VoiceAndSpeed(s: CoachSettings, onChange: (CoachSettings) -> Unit, label: String?, onVoice: () -> Unit) {
    ListRow("Voice", label ?: "…", divider = false, onClick = onVoice)
    SpeechSlider("Speed", s.speech.rate, Speech.MinRate..Speech.MaxRate) {
        onChange(s.copy(speech = s.speech.copy(rate = it)))
    }
    SpeechSlider("Pitch", s.speech.pitch, Speech.MinPitch..Speech.MaxPitch) {
        onChange(s.copy(speech = s.speech.copy(pitch = it)))
    }
    DimText("Speed 0.8× to 1.5×, pitch 0.7× to 1.3×.")
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
