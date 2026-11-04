package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.review.SessionListDay
import com.flappedear.betterdriver.analysis.review.SessionListTrackDay
import com.flappedear.betterdriver.analysis.review.sessionListEntry
import com.flappedear.betterdriver.analysis.review.sessionListDays
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A day's heading (UI-308 A): "Today", "Yesterday" or "Fri 29 Aug", with the track when the day had
 * one, as a section title; then, for each track driven at least twice that day, a Track day card
 * (FBBD-315): the day's best lap in purple and each session's best as a bar ([facts]). The card
 * opens the whole day.
 */
@Composable
internal fun DayHeading(
    day: SessionListDay,
    onTrackDay: (List<String>) -> Unit,
    facts: Map<String, SessionFacts> = emptyMap(),
) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(day.title)
        day.trackDays.forEach { TrackDayCard(it, facts, named = day.trackOnRows, onTrackDay) }
    }
}

@Composable
private fun TrackDayCard(
    day: SessionListTrackDay,
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

/** The Sessions list's days (shared with iOS, FBBD-360), in the phone's time zone, newest first as listed. */
internal fun sessionDays(
    sessions: List<SessionSummary>,
    facts: Map<String, SessionFacts> = emptyMap(),
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<SessionListDay> = sessionListDays(
    sessions.map {
        val day = Instant.ofEpochMilli(it.info.startUtcMillis).atZone(zone).toLocalDate()
        sessionListEntry(
            it.id,
            day.toEpochDay(),
            it.info.track,
            facts[it.id],
            it.info.startUtcMillis,
            recording = it.state == SessionState.RECORDING,
        )
    },
    today.toEpochDay(),
) { DayFormat.format(LocalDate.ofEpochDay(it)) }
