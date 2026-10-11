package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.quicker
import com.flappedear.betterdriver.analysis.summary.slower
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import kotlin.math.roundToLong

private const val MillisPerSecond = 1000.0

/**
 * Session summary (FBBD-122, FBBD-123, UI-70): this session's best lap against the best of every
 * earlier session at the track, how repeatable its laps were against the session before, and the
 * corners and straights whose typical time moved most since then.
 */
@Composable
internal fun Progress(progress: SessionProgress, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Against earlier sessions here")
        val earlierBest = progress.earlierBestSeconds
        val delta = progress.bestDeltaSeconds
        when {
            progress.earlierSessions == 0 -> Dim("First session at this track.")
            earlierBest == null || delta == null ->
                Dim("No counted lap in the ${progress.earlierSessions} earlier sessions here.")
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress.newBest) "New best lap here" else "Best lap against your best here",
                        Modifier.weight(1f),
                        color = c.text,
                    )
                    Text(
                        formatDelta(delta),
                        color = if (isDeltaGain(delta)) c.gain else c.loss,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Dim("Was ${formatLapTime((earlierBest * MillisPerSecond).roundToLong())}, ${progress.earlierBestLabel}")
            }
        }
        progress.lapSpreadSeconds?.let { spread ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lap spread ${seconds(spread)} s", Modifier.weight(1f), color = c.text)
                progress.spreadDeltaSeconds?.let { d ->
                    Text(
                        if (d < 0) "steadier by ${seconds(-d)} s" else "less steady by ${seconds(d)} s",
                        color = if (d < 0) c.gain else c.loss,
                    )
                }
            }
            progress.previousLabel?.takeIf { progress.spreadDeltaSeconds != null }?.let { Dim("Against $it") }
        }
        Changes(progress)
        Dim("${progress.eligibleLapCount} counted laps. Spread: the middle half of your lap times.")
    }
}

/** At most this many corners or straights in each of the quicker and slower lists. */
private const val ChangeRows = 3

/** The corners and straights whose typical time moved most since the session before. */
@Composable
private fun Changes(progress: SessionProgress) {
    val c = TrackTheme.colors
    if (progress.changes.isEmpty()) return
    val quicker = quicker(progress.changes).take(ChangeRows)
    val slower = slower(progress.changes).take(ChangeRows)
    Dim("Typical time by corner since ${progress.previousLabel.orEmpty()}")
    if (quicker.isEmpty() && slower.isEmpty()) {
        Dim("Every corner and straight within 0.05 s of the session before.")
        return
    }
    for (change in quicker + slower) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.name, Modifier.weight(1f), color = c.text)
            Text(
                formatDelta(change.deltaSeconds),
                color = if (change.deltaSeconds < 0) c.gain else c.loss,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
