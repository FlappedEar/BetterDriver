package com.flappedear.betterdriver.sessionanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.shell.HideSessionList
import com.flappedear.betterdriver.shell.SessionListToggle
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.ShowSessionList
import com.flappedear.betterdriver.shell.ShellTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The bar over a session's analysis, as in the iOS app: the session's start time and its track for a
 * title, and a menu with the ways on from it, the track's progression (FBBD-181, when the session is
 * at a track) and the coach's cues (FBBD-99). On a tablet [listToggle] hides the session list beside
 * it, or shows it again, as the iPad's.
 */
@Composable
internal fun AnalysisTopBar(
    title: String,
    onBack: (() -> Unit)?,
    listToggle: SessionListToggle? = null,
    onProgression: (() -> Unit)?,
    onCoachReview: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ShellTopBar(
        title,
        onBack = onBack,
        navigation = listToggle?.let { { SessionListButton(it) } },
    ) {
        Box {
            IconButton(onClick = { open = true }) {
                Icon(
                    ShellIcons.More,
                    contentDescription = SessionMenu,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                onProgression?.let { go ->
                    DropdownMenuItem(
                        text = { Text("Progression at this track") },
                        onClick = {
                            open = false
                            go()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Coach review") },
                    onClick = {
                        open = false
                        onCoachReview()
                    },
                )
            }
        }
    }
}

/** Hides the session list beside the analysis, or shows it again (Arek 2026-10-31). */
@Composable
private fun SessionListButton(toggle: SessionListToggle) {
    IconButton(onClick = toggle.toggle) {
        Icon(
            ShellIcons.List,
            contentDescription = if (toggle.hidden) ShowSessionList else HideSessionList,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The analysis's title: the session's start time, then its track when it has one ("14:02 · Jastrząb"),
 * as the iOS app's. "Lap analysis" while the session is not known.
 */
internal fun analysisTitle(
    startUtcMillis: Long?,
    track: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val time = startUtcMillis?.let { format.format(Instant.ofEpochMilli(it).atZone(zone)) }
    return listOfNotNull(time, track?.ifBlank { null }).joinToString(" · ").ifEmpty { "Lap analysis" }
}

/** The menu button's description, which the smoke test waits for once a session opens. */
const val SessionMenu = "Session menu"
