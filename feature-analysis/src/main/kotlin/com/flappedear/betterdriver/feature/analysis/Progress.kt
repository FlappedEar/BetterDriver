package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.quicker
import com.flappedear.betterdriver.analysis.summary.slower
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.format.formatDelta
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.format.isDeltaGain
import kotlin.math.roundToLong

private const val MillisPerSecond = 1000.0

/** The link from a session's summary to the progression at its track (FBBD-181), for tests. */
const val ProgressionLinkTag = "progression-link"
const val ProgressionLinkText = "Progress over every session here"

/** The typical (median) lap on the session summary (FBBD-175), for tests. */
const val TypicalLapTag = "typical-lap"

/**
 * Session summary (FBBD-122, FBBD-123, UI-70): this session's best lap against the best of every
 * earlier session at the track, how consistent its laps were (the typical, median, lap and the spread
 * of the middle half, FBBD-175) against the session before, and the corners and straights whose
 * typical time moved most since then. [onProgression], when given, opens the progression over every
 * session at the track (FBBD-181).
 */
@Composable
internal fun Progress(progress: SessionProgress, modifier: Modifier, onProgression: (() -> Unit)? = null) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Against earlier sessions here")
        val earlierBest = progress.earlierBestSeconds
        val delta = progress.bestDeltaSeconds
        when {
            progress.earlierSessions == 0 || earlierBest == null || delta == null -> Dim(bestText(progress))
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bestText(progress),
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
        // How consistent the session was (FBBD-175): its typical (median) lap and the spread around it.
        progress.lapMedianSeconds?.let {
            Text(
                "Typical lap ${formatLapTime((it * MillisPerSecond).roundToLong())}",
                Modifier.testTag(TypicalLapTag),
                color = c.text,
            )
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
        Dim(
            "${progress.eligibleLapCount} counted laps. Typical: the median lap. " +
                "Spread: the middle half of your lap times.",
        )
        onProgression?.let { open ->
            TextButton(onClick = open, modifier = Modifier.testTag(ProgressionLinkTag)) {
                Text(ProgressionLinkText)
            }
        }
    }
}

/**
 * The best lap against the earlier sessions here, in words. With [SessionProgress.earlierLimited]
 * only the newest sessions were read, so the best is theirs rather than of every session here.
 */
private fun bestText(progress: SessionProgress): String {
    val limited = progress.earlierLimited
    val sessions = "${if (limited) "last" else "earlier"} ${progress.earlierSessions} sessions"
    return when {
        progress.earlierSessions == 0 && limited -> "No recent earlier session at this track."
        progress.earlierSessions == 0 -> "First session at this track."
        progress.bestDeltaSeconds == null -> "No counted lap in the $sessions here."
        limited && progress.newBest -> "New best of your $sessions here"
        limited -> "Best lap against your $sessions here"
        progress.newBest -> "New best lap here"
        else -> "Best lap against your best here"
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
