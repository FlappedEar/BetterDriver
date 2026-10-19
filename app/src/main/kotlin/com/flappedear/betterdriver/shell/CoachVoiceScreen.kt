package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme

/**
 * The coach's voice (FBBD-240, UI-90 A), opened from the Voice row of the Spoken coach page: the
 * phone's offline English voices grouped by accent ([groups], heading to rows; null while they are
 * read), the one in use ([selected]) marked. ▶ says the sample cue in that voice ([onPlay]); tapping a
 * voice uses it ([onPick]). [onMoreVoices] opens Android's text-to-speech settings, where more voices
 * are downloaded.
 */
@Composable
fun CoachVoiceScreen(
    groups: List<Pair<String, List<VoiceRow>>>?,
    selected: String?,
    onPick: (String) -> Unit,
    onPlay: (String) -> Unit,
    onMoreVoices: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Voice", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                groups == null -> DimText("Reading the phone's voices…")
                groups.isEmpty() -> DimText("This phone has no English voice that works without internet.")
                else -> groups.forEach { (accent, voices) ->
                    SectionLabel(accent, Modifier.padding(start = 4.dp, top = 4.dp))
                    ShellCard { voices.forEach { VoiceLine(it, it.name == selected, onPick, onPlay) } }
                }
            }
            DimText("Voices installed on this phone. ▶ says the sample cue; tap a voice to use it.")
            TextButton(onClick = onMoreVoices) { Text("More voices in Android settings") }
        }
    }
}

@Composable
private fun VoiceLine(v: VoiceRow, on: Boolean, onPick: (String) -> Unit, onPlay: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = on, onClick = { onPick(v.name) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = on, onClick = null)
        Text(v.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedIconButton(onClick = { onPlay(v.name) }) {
            Icon(ShellIcons.Play, contentDescription = "Play ${v.label}", modifier = Modifier.size(20.dp))
        }
    }
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun CoachVoiceScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            CoachVoiceScreen(
                listOf(
                    "English (United Kingdom)" to (1..4).map { VoiceRow("gb$it", "Voice $it") },
                    "English (United States)" to (1..2).map { VoiceRow("us$it", "Voice $it") },
                ),
                selected = "gb3",
                onPick = {},
                onPlay = {},
                onMoreVoices = {},
                onBack = {},
            )
        }
    }
}
