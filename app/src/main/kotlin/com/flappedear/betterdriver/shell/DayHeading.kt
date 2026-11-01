package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.HealthCard
import com.flappedear.betterdriver.core.ui.component.HealthDetail
import com.flappedear.betterdriver.core.ui.component.HealthIcon
import com.flappedear.betterdriver.core.ui.component.HealthValue
import com.flappedear.betterdriver.core.ui.component.HealthValueRow
import com.flappedear.betterdriver.core.ui.component.MiniBars
import com.flappedear.betterdriver.core.ui.component.SectionTitle
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.time.LocalDate

/**
 * A day's heading (UI-308 A): "Today", "Yesterday" or "Fri 29 Aug", with the track when the day had
 * one, as a section title; then, for each track driven at least twice that day, a Track day card
 * (FBBD-315): the day's best lap in purple and each session's best as a bar ([facts]). The card
 * opens the whole day.
 */
@Composable
internal fun DayHeading(
    day: LocalDate,
    list: List<SessionSummary>,
    onTrackDay: (List<String>) -> Unit,
    facts: Map<String, SessionFacts> = emptyMap(),
) {
    val days = trackDays(list)
    val tracks = list.map { it.info.track }.filter { it.isNotBlank() }.distinct()
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tracks.singleOrNull()?.let { "${dayLabel(day)} · $it" } ?: dayLabel(day))
        days.forEach { TrackDayCard(it, facts, named = tracks.size > 1, onTrackDay) }
    }
}

@Composable
private fun TrackDayCard(
    day: TrackDayLinkUi,
    facts: Map<String, SessionFacts>,
    named: Boolean,
    onTrackDay: (List<String>) -> Unit,
) {
    val c = TrackTheme.colors
    val bests = day.ids.mapNotNull { facts[it]?.bestMillis }
    HealthCard(
        Modifier.testTag(TrackDayLinkTag),
        title = if (named) "Track day · ${day.track}" else "Track day",
        icon = HealthIcon.Laps,
        trailing = "${day.ids.size} sessions",
        onClick = { onTrackDay(day.ids) },
        onClickLabel = "Track day",
    ) {
        HealthValueRow(
            chart = { MiniBars(bests.map { it.toDouble() }, MaterialTheme.colorScheme.primary, c.best) },
        ) {
            if (bests.isEmpty()) {
                HealthDetail("The whole day at ${day.track}")
            } else {
                HealthValue(formatLapTime(bests.min()), color = c.best)
                HealthDetail("best of the day")
            }
        }
    }
}

/** The test tag of a day's Track day card. */
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
