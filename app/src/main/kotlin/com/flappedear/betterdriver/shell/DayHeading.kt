package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.time.LocalDate

/**
 * A day's heading: "Today", "Yesterday" or "Fri 29 Aug", and for each track driven at least twice
 * that day a "Track day ›" link to the whole day (FBBD-315, UI-210 B).
 */
@Composable
internal fun DayHeading(day: LocalDate, list: List<SessionSummary>, onTrackDay: (List<String>) -> Unit) {
    val days = trackDays(list)
    Column(Modifier.padding(top = 12.dp, start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val one = days.singleOrNull()
            SectionLabel(
                if (one != null) "${dayLabel(day)} · ${one.track}" else dayLabel(day),
                Modifier.weight(1f),
            )
            if (one != null) TrackDayLink("Track day ›", one.ids, onTrackDay)
        }
        if (days.size > 1) {
            days.forEach { TrackDayLink("Track day at ${it.track} ›", it.ids, onTrackDay) }
        }
    }
}

@Composable
private fun TrackDayLink(text: String, ids: List<String>, onTrackDay: (List<String>) -> Unit) {
    TextButton(onClick = { onTrackDay(ids) }, modifier = Modifier.testTag(TrackDayLinkTag)) {
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}

/** The test tag of a day heading's "Track day ›" link. */
internal const val TrackDayLinkTag = "track-day-link"

/** One track driven more than once on one day: its name and its sessions, oldest first. */
internal data class TrackDayLinkUi(val track: String, val ids: List<String>)

/**
 * The tracks of one day's [sessions] (as listed, newest first) driven in at least two sessions that
 * are not recording, most sessions first; a session with no track name takes no part.
 */
internal fun trackDays(sessions: List<SessionSummary>): List<TrackDayLinkUi> =
    sessions.filter { it.state != SessionState.RECORDING && it.info.track.isNotBlank() }
        .groupBy { it.info.track }
        .filterValues { it.size >= 2 }
        .map { (track, list) -> TrackDayLinkUi(track, list.sortedBy { it.info.startUtcMillis }.map { it.id }) }
        .sortedByDescending { it.ids.size }
