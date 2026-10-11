package com.flappedear.betterdriver.shell

import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.recorder.store.SessionSummary

/** Cue counts of coached sessions, by id, and opening one's cue review (FBBD-99). */
data class SessionCoach(
    val cues: Map<String, Int> = emptyMap(),
    val onOpen: (SessionSummary) -> Unit = {},
)

/** "Coach review · 12 cues" on a session the coach spoke in (FBBD-99). */
@Composable
internal fun SessionCoachRow(s: SessionSummary, coach: SessionCoach) {
    val n = coach.cues[s.id] ?: return
    ListRow("Coach review", if (n == 1) "1 cue" else "$n cues", onClick = { coach.onOpen(s) })
}
