package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.progressBestText
import com.flappedear.betterdriver.analysis.summary.quicker
import com.flappedear.betterdriver.analysis.summary.slower
import com.flappedear.betterdriver.analysis.summary.typicalLapHighlight
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.CompareBar
import com.flappedear.betterdriver.core.ui.component.CompareBars
import com.flappedear.betterdriver.core.ui.component.HealthCard
import com.flappedear.betterdriver.core.ui.component.HealthIcon
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
    // The Highlights card (UI-307 A): one sentence on the typical lap and two bars to compare, then the detail.
    val highlight = typicalLapHighlight(progress)
    HealthCard(modifier, title = if (highlight != null) "Typical lap" else EarlierTitle, icon = HealthIcon.Laps) {
        if (highlight != null) Highlight(highlight, progress)
        EarlierBest(progress)
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

private const val EarlierTitle = "Against earlier sessions here"

/** This session's best lap against the earlier sessions' best, and when that was set. */
@Composable
private fun EarlierBest(progress: SessionProgress) {
    val c = TrackTheme.colors
    val earlierBest = progress.earlierBestSeconds
    val delta = progress.bestDeltaSeconds
    when {
        progress.earlierSessions == 0 || earlierBest == null || delta == null -> Dim(progressBestText(progress))
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progressBestText(progress),
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
}

/** The highlight in one sentence, its two bars, and the heading of the detail under them. */
@Composable
private fun Highlight(text: String, progress: SessionProgress) {
    Text(
        text,
        color = TrackTheme.colors.text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    TypicalLapBars(progress)
    TrackLabel(EarlierTitle)
}

/** This session's typical lap against the session before's, as two bars, the longer lap the longer bar. */
@Composable
private fun TypicalLapBars(progress: SessionProgress) {
    val now = progress.lapMedianSeconds ?: return
    val before = progress.previousMedianSeconds ?: return
    // From 90 % of the quicker lap, so a second in a lap of eighty shows.
    val floor = minOf(now, before) * BarFloorShare
    val top = maxOf(now, before) - floor
    fun share(v: Double) = ((v - floor) / top).toFloat()
    CompareBars(
        listOf(
            CompareBar("This session", lapText(now), share(now), MaterialTheme.colorScheme.primary),
            CompareBar(progress.previousLabel.orEmpty(), lapText(before), share(before), BeforeBarColor),
        ),
    )
}

private fun lapText(seconds: Double) = formatLapTime((seconds * MillisPerSecond).roundToLong())

private const val BarFloorShare = 0.9

/** The earlier session's bar: Apple's grey. */
private val BeforeBarColor = Color(0xFF636366)

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
