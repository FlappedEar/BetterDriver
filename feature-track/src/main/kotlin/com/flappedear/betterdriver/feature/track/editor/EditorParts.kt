package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.live.LapBook
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.track.Track
import java.util.Locale

private const val MillisPerSecond = 1_000.0
private const val MillisPerMinute = 60_000L

/** Colours from the approved mockups (UI-23 to UI-26). */
internal object EditorColors {
    val Accent = Color(0xFF4FB8D6)
    val Line = Color(0xFFF2F5F7)
    val Pit = Color(0xFFC9D3DA)
    val Halo = Color(0xFF0B0F12)

    /** The recorded lap on the map: a colour no satellite photo or street map has. */
    val Lap = Color(0xFFFF3DD8)

    /** The track's corners on the map and in the list (FBBD-179). */
    val Corner = Color(0xFFF2B84B)

    /** Its straights: faint, so the corners stand out. */
    val Straight = Color(0x99C9D3DA)
}

@Composable
internal fun LineSwatch(ref: LineRef) {
    val pit = ref == LineRef.PitEntry || ref == LineRef.PitExit
    Box(
        Modifier
            .size(width = 18.dp, height = 4.dp)
            .background(if (pit) EditorColors.Pit else EditorColors.Line, RoundedCornerShape(2.dp)),
    )
}

@Composable
internal fun RetimeNote(sessions: Int, laps: Int, pointToPoint: Boolean = false) {
    val lap = if (pointToPoint) "run" else "lap"
    val text = when (sessions) {
        0 -> "No sessions here yet. ${lap.replaceFirstChar(Char::uppercaseChar)}s are timed with these lines " +
            "from the next session."
        1 -> "Saved sessions here are re-timed when you save: 1 session, ${plural(laps, lap)}."
        else -> "Saved sessions here are re-timed when you save: $sessions sessions, ${plural(laps, lap)}."
    }
    DimLine(text, Modifier.padding(top = 6.dp))
}

/**
 * The newest session's laps with [track]'s lines as they are now (UI-25 option A); runs on a
 * point-to-point track (FBBD-155).
 */
@Composable
internal fun SessionLaps(title: String?, book: LapBook?, track: Track) {
    val sectorCount = track.sectorCount
    if (title == null) {
        DimLine("No sessions here yet.", Modifier.padding(top = 12.dp))
        return
    }
    Text(
        "Session $title · re-timed live",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    val laps = book?.laps.orEmpty()
    if (laps.isEmpty()) {
        DimLine(
            if (track.pointToPoint) {
                "No runs with these lines. Place the start and finish lines on your drive."
            } else {
                "No laps with these lines. Place the start / finish line on the lap."
            },
        )
        return
    }
    val best = book?.best
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        val first = if (track.pointToPoint) "Run" else "Lap"
        LapRow(listOf(first, "Time") + List(sectorCount) { "S${it + 1}" }, header = true)
        for (lap in laps) {
            val time = when (lap.kind) {
                LapKind.Timed -> formatLapTime(lap.timeMillis)
                LapKind.Out -> "Out"
                LapKind.In -> "In"
            }
            val sectors = lap.sectorMicros.map { s -> s?.let { formatSector(it / 1_000) } ?: "–" }
            LapRow(listOf(lap.number.toString(), time) + sectors, best = lap == best)
        }
    }
}

@Composable
internal fun LapRow(cells: List<String>, header: Boolean = false, best: Boolean = false) {
    val color = when {
        header -> MaterialTheme.colorScheme.onSurfaceVariant
        best -> TrackTheme.colors.best
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                color = color,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (header || best) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier.width(if (i == 0) 40.dp else 72.dp),
            )
        }
    }
}

@Composable
internal fun DimLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

internal fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/** Sector time as 38.20, or 1:02.35 from a minute. */
internal fun formatSector(millis: Long): String =
    if (millis < MillisPerMinute) {
        String.format(Locale.ROOT, "%.2f", millis / MillisPerSecond)
    } else {
        formatLapTime(millis)
    }
