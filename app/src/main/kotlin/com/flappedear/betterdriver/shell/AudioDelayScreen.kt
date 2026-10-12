package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.AudioDelay
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.DelayResult
import com.flappedear.betterdriver.coach.TapStats
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import kotlin.math.roundToInt

/** At this width or wider (a tablet, or a phone on its side) the explanation sits beside the tap pad. */
private val WideMinWidth = 600.dp
private val SidePaneWidth = 360.dp

/** Which run of the headset delay test (FBBD-49) is next or under way. */
enum class DelayStep { Speaker, Headset, Done }

/**
 * What the headset delay test shows (FBBD-49). [taps] counted in the run under way; [speaker] and
 * [headsetRun] are the finished runs' taps; [result] the delay once both are in. [headset] is the
 * connected headset's name, null if sound goes to the phone. [retry] when the last run had too few
 * steady taps and is to be done again.
 */
data class AudioDelayUi(
    val step: DelayStep = DelayStep.Speaker,
    val running: Boolean = false,
    val taps: Int = 0,
    val speaker: TapStats? = null,
    val headsetRun: TapStats? = null,
    val result: DelayResult? = null,
    val headset: String? = null,
    val retry: Boolean = false,
)

/**
 * The test after a run's taps (FBBD-49): the speaker run leads to the headset run, the headset run to
 * the result. A run with too few steady taps is done again.
 */
internal fun AudioDelayUi.afterRun(delays: List<Long>): AudioDelayUi {
    val stats = AudioDelay.stats(delays) ?: return copy(running = false, taps = 0, retry = true)
    val base = copy(running = false, retry = false)
    val speakerRun = speaker
    return when {
        step == DelayStep.Speaker -> base.copy(step = DelayStep.Headset, taps = 0, speaker = stats)
        speakerRun != null ->
            base.copy(step = DelayStep.Done, headsetRun = stats, result = AudioDelay.delay(stats, speakerRun))
        else -> base.copy(step = DelayStep.Speaker, taps = 0)
    }
}

/**
 * The headset audio-delay test (FBBD-49), opened from the spoken coach settings. A click plays at random
 * moments and Arek taps the big pad when he hears it, [AudioDelay.Taps] times: first on the phone
 * speaker for his reaction time, then on the headset. The headset's delay is the difference, and "Use
 * as audio delay" saves it. On a phone the explanation and runs are above the pad; on a wide screen
 * beside it. [onTap] gets the touch time in uptime milliseconds, from the touch event itself.
 */
@Composable
fun AudioDelayScreen(
    ui: AudioDelayUi,
    currentMillis: Int,
    onStart: () -> Unit,
    onTap: (uptimeMillis: Long) -> Unit,
    onUse: (millis: Int) -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Headset delay", onBack = onBack)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (maxWidth >= WideMinWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.width(SidePaneWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { Info(ui) }
                    TapPad(ui, currentMillis, onStart, onTap, onUse, onAgain, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { Info(ui) }
                    TapPad(
                        ui, currentMillis, onStart, onTap, onUse, onAgain,
                        Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Info(ui: AudioDelayUi) {
    if (!ui.running) {
        ShellCard {
            DimText(
                "A click plays at random moments; tap the pad as soon as you hear it, ${AudioDelay.Taps} times. " +
                    "Your tap includes your reaction time, so the test first measures that on the phone " +
                    "speaker, which has almost no delay, then measures again with the headset. The headset's " +
                    "delay is the difference. Taps far from the rest, such as a missed click, are left out.",
            )
        }
    }
    ShellCard {
        Run("1 · Phone speaker", ui.speaker, ui.step == DelayStep.Speaker)
        Run("2 · Headset", ui.headsetRun, ui.step == DelayStep.Headset)
        val out = ui.headset
        if (out != null) {
            DimText("Sound goes to $out.")
        } else {
            DimText("No headset connected: sound goes to the phone.", color = TrackTheme.colors.warning)
        }
    }
}

@Composable
private fun Run(label: String, stats: TapStats?, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (current) FontWeight.SemiBold else null),
            modifier = Modifier.weight(1f),
        )
        // "231 ms ± 20", and how many taps were left out.
        val value = stats?.let { s ->
            val out = if (s.discarded == 0) "" else " · ${s.discarded} left out"
            "${s.meanMillis.roundToInt()} ms ± ${s.spreadMillis.roundToInt()}$out"
        }
        DimText(value ?: if (current) "Next" else "—")
    }
}

@Composable
private fun TapPad(
    ui: AudioDelayUi,
    currentMillis: Int,
    onStart: () -> Unit,
    onTap: (Long) -> Unit,
    onUse: (Int) -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val pad = if (ui.running) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture { tap(awaitFirstDown(requireUnconsumed = false).uptimeMillis) }
        }
    } else {
        Modifier
    }
    val color = if (ui.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Box(
        modifier.background(color, RoundedCornerShape(24.dp)).then(pad).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                ui.result != null -> Result(ui.result, currentMillis, onUse, onAgain)
                ui.running -> Running(ui.taps)
                else -> Ready(ui, onStart)
            }
        }
    }
}

@Composable
private fun Running(taps: Int) {
    Text(
        "Tap when you hear the click",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        textAlign = TextAlign.Center,
    )
    Text(
        "$taps of ${AudioDelay.Taps}",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun Ready(ui: AudioDelayUi, onStart: () -> Unit) {
    val speaker = ui.step == DelayStep.Speaker
    Text(
        if (speaker) "Step 1 of 2 · phone speaker" else "Step 2 of 2 · headset",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    DimText(
        if (speaker) {
            "The clicks play on the phone speaker. Hold the phone as you will tap it."
        } else {
            "Put the helmet headset on; the clicks play on it, as the coach's voice does."
        },
    )
    if (ui.retry) DimText("Too few steady taps. Try this step again.", color = TrackTheme.colors.warning)
    Button(onClick = onStart) { Text("Start") }
}

@Composable
private fun Result(result: DelayResult, currentMillis: Int, onUse: (Int) -> Unit, onAgain: () -> Unit) {
    DimText("Headset delay")
    Text("${result.millis} ms", style = MaterialTheme.typography.displaySmall)
    DimText("± ${result.spreadMillis} ms · now set to $currentMillis ms")
    if (result.capped) {
        DimText("More than the coach allows: capped at ${CoachSettings.MaxAudioDelayMillis / 1_000} s.")
    }
    Button(onClick = { onUse(result.millis) }) { Text("Use as audio delay") }
    OutlinedButton(onClick = onAgain) { Text("Measure again") }
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun AudioDelayScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AudioDelayScreen(
                AudioDelayUi(
                    step = DelayStep.Headset,
                    running = true,
                    taps = 7,
                    speaker = TapStats(231.0, 21.0, 19, 1),
                    headset = "Cardo PACKTALK",
                ),
                currentMillis = 300,
                onStart = {},
                onTap = {},
                onUse = {},
                onAgain = {},
                onBack = {},
            )
        }
    }
}

@Preview(widthDp = 900, heightDp = 600)
@Composable
private fun AudioDelayResultPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AudioDelayScreen(
                AudioDelayUi(
                    step = DelayStep.Done,
                    speaker = TapStats(231.0, 21.0, 19, 1),
                    headsetRun = TapStats(418.0, 30.0, 20, 0),
                    result = DelayResult(190, 8, capped = false),
                    headset = "Cardo PACKTALK",
                ),
                currentMillis = 300,
                onStart = {},
                onTap = {},
                onUse = {},
                onAgain = {},
                onBack = {},
            )
        }
    }
}
