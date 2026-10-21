package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.NumberFont
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.DeleteState
import com.flappedear.betterdriver.recording.ExportFormat
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.formatDuration
import com.flappedear.betterdriver.sharing.TransferState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Saved sessions, newest first, each with VBO export (FBBD-66, FBBD-71), plus its video when it
 * has any (FBBD-25), and CSV and GPX export (FBBD-150), all from one Export menu. A session
 * recovered after a crash is marked and exports like any other. Built from the approved shell
 * parts; the layout is on the UI review page for approval.
 *
 * A finished session also shows its GoPro video (FBBD-22): Download for files still on an Open GoPro
 * camera (UI-31) and Add for a GoPro started by hand (UI-32), per camera. Both are off while a session records.
 *
 * Delete (FBBD-90, UI-48) asks first, saying how much space the session and its video free, then
 * removes both. It is off while the session records, exports or fetches GoPro video.
 *
 * Share sends the session with its video and track to the other device (FBBD-23, UI-55): Send
 * through the share sheet (Quick Share), or Save file. Import session at the top brings one in: a
 * BetterDriver session file, or a VBO, GPX or RaceChrono CSV file from another app (FBBD-164). A
 * session made from another app's file is labelled Imported, with its format and file name.
 *
 * A session the coach spoke in opens its cue review (FBBD-99): each cue and whether the next pass
 * through that corner got better.
 *
 * A session with video also exports Video with data (FBBD-152): a lap or all of it with the data drawn
 * in. Its progress shows on the card with Cancel; meanwhile the card's other actions wait.
 *
 * The list is one row per session, grouped by day (UI-139 C): when and where, the car, how long, its
 * timed laps and what was recorded ([facts], once read), and its best lap in purple. A tap opens lap
 * analysis; ⋮ opens a sheet with everything else the session offers (SessionActions). A row says
 * while something runs for it, such as an export or a GoPro download.
 *
 * On a tablet the list sits beside the lap analysis of the session [selectedId], whose row is outlined (FBBD-40).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    exports: Map<String, ExportState>,
    onExport: (SessionSummary, ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
    withVideo: Set<String> = emptySet(),
    goPro: GoProCardState = GoProCardState(),
    deleting: SessionDeleting = SessionDeleting(),
    transfer: SessionTransferState = SessionTransferState(),
    coach: SessionCoach = SessionCoach(),
    videoExport: SessionVideoExport = SessionVideoExport(),
    onAnalyse: (SessionSummary) -> Unit = {},
    selectedId: String? = null,
    facts: Map<String, SessionFacts> = emptyMap(),
    header: @Composable () -> Unit = {},
) {
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Sessions") {
            TextButton(onClick = transfer.onImport, enabled = transfer.importEnabled) { Text("Import session") }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header()
            ImportCard(transfer.incoming)
        }
        if (sessions.isEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp)) { ShellCard { DimText("No sessions yet") } }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                byDay(sessions).forEach { (day, list) ->
                    item(key = "day-$day") {
                        SectionLabel(dayLabel(day), Modifier.padding(top = 12.dp, start = 4.dp))
                    }
                    items(list, key = { it.id }) { s ->
                        SessionRow(
                            s,
                            facts[s.id],
                            video = s.id in withVideo,
                            // With its sheet open, the sheet says it: a text shows once.
                            status = rowStatus(s, exports[s.id], goPro, deleting, transfer, videoExport)
                                .takeUnless { actionsFor == s.id },
                            selected = s.id == selectedId,
                            opens = opensAnalysis(s, deleting.states[s.id]),
                            onAnalyse = { onAnalyse(s) },
                            onActions = { actionsFor = s.id },
                        )
                    }
                }
            }
        }
    }
    sessions.firstOrNull { it.id == actionsFor }?.let { shown ->
        ActionsSheet(shown, exports, withVideo, onExport, goPro, deleting, transfer, onAnalyse, coach, videoExport) {
            actionsFor = null
        }
    }
}

/** The sheet of [shown]'s actions. Opening its lap analysis or a file export closes it ([onClose]). */
@Suppress("LongParameterList") // What the actions need, passed on from the rows.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(
    shown: SessionSummary,
    exports: Map<String, ExportState>,
    withVideo: Set<String>,
    onExport: (SessionSummary, ExportFormat) -> Unit,
    goPro: GoProCardState,
    deleting: SessionDeleting,
    transfer: SessionTransferState,
    onAnalyse: (SessionSummary) -> Unit,
    coach: SessionCoach,
    videoExport: SessionVideoExport,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            SessionActions(
                shown, exports[shown.id], shown.id in withVideo,
                // The save dialog comes next, and the row shows how the export went.
                onExport = { s, format ->
                    onClose()
                    onExport(s, format)
                },
                goPro, deleting, transfer,
                onAnalyse = { s ->
                    onClose()
                    onAnalyse(s)
                },
                coach, videoExport,
            )
        }
    }
}

/** Delete state and action for the whole Sessions list (FBBD-90). */
data class SessionDeleting(
    /** Bytes on disk of each session, video included. */
    val sizes: Map<String, Long> = emptyMap(),
    val states: Map<String, DeleteState> = emptyMap(),
    val onDelete: (SessionSummary) -> Unit = {},
)

/** Sessions by the day they started on, in the phone's time zone, newest first as listed. */
internal fun byDay(
    sessions: List<SessionSummary>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<LocalDate, List<SessionSummary>>> =
    sessions.groupBy { Instant.ofEpochMilli(it.info.startUtcMillis).atZone(zone).toLocalDate() }.toList()

/** "Today", "Yesterday", else "Sun 19 Oct". */
internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DayFormat.format(day)
}

private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/** "16:23 · Silesia Ring": when the session started, and where. */
internal fun rowTitle(s: SessionSummary, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = TimeFormat.format(Instant.ofEpochMilli(s.info.startUtcMillis).atZone(zone))
    return listOf(time, s.info.track).filter { it.isNotBlank() }.joinToString(" · ")
}

/** "Corolla · 22 min · 14 laps · Video · OBD · HR": the car, how long, and what was recorded. */
internal fun rowDetail(s: SessionSummary, facts: SessionFacts?, video: Boolean): String = listOfNotNull(
    s.info.car.ifBlank { null },
    s.durationMicros?.let(::formatDuration),
    facts?.laps?.takeIf { it > 0 }?.let { if (it == 1) "1 lap" else "$it laps" },
    "Video".takeIf { video },
    "OBD".takeIf { facts?.obd == true },
    "HR".takeIf { facts?.heartRate == true },
).joinToString(" · ")

/** What runs for a session now, for its row; null when nothing does. */
@Suppress("LongParameterList")
private fun rowStatus(
    s: SessionSummary,
    export: ExportState?,
    goPro: GoProCardState,
    deleting: SessionDeleting,
    transfer: SessionTransferState,
    videoExport: SessionVideoExport,
): String? = when {
    deleting.states[s.id] == DeleteState.Running -> "Deleting…"
    export == ExportState.Running -> "Exporting…"
    transfer.outgoing[s.id]?.running == true -> "Sending…"
    videoExport.running == s.id -> "Rendering video with data…"
    goPro.work[s.id] != null -> "Getting GoPro video…"
    goPro.sessions[s.id]?.cameras?.any { it.onCamera } == true -> "GoPro video still on the camera"
    // How the last export or delete ended ("Saved", "Export failed"), as the sheet says it.
    else -> cardStatus(deleting.states[s.id], export).first.ifEmpty { null }
}

@Suppress("LongParameterList")
@Composable
private fun SessionRow(
    s: SessionSummary,
    facts: SessionFacts?,
    video: Boolean,
    status: String?,
    selected: Boolean,
    opens: Boolean,
    onAnalyse: () -> Unit,
    onActions: () -> Unit,
) {
    val c = TrackTheme.colors
    // The row whose analysis shows beside the list on a tablet is outlined (FBBD-40).
    val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, outline, RowShape)
            .clickable(enabled = opens, onClickLabel = "Lap analysis", onClick = onAnalyse),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rowTitle(s), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                val detail = rowDetail(s, facts, video)
                if (detail.isNotEmpty()) DimText(detail)
                ImportedLine(s.info)
                when (s.state) {
                    SessionState.RECORDING -> DimText("Recording", color = c.loss)
                    SessionState.RECOVERED, SessionState.INTERRUPTED -> DimText("Recovered", color = c.warning)
                    SessionState.FINISHED -> Unit
                }
                status?.let { DimText(it) }
            }
            facts?.bestMillis?.let { best ->
                Text(
                    formatLapTime(best),
                    color = c.best,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = NumberFont,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            IconButton(onClick = onActions, modifier = Modifier.semantics { contentDescription = ActionsLabel }) {
                Text(
                    "⋮",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val RowShape = RoundedCornerShape(8.dp)

/** What TalkBack and the tests call a row's ⋮. */
const val ActionsLabel = "Session actions"

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun SessionsScreenPreview() {
    fun session(id: String, start: Long, state: SessionState, minutes: Long?) = SessionSummary(
        id,
        File(id),
        SessionInfo(start, car = "Club car", track = "Tor Poznań"),
        state,
        minutes?.let { it * 60_000_000 },
    )
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            SessionsScreen(
                sessions = listOf(
                    session("c", 1_760_190_000_000, SessionState.RECORDING, null),
                    session("b", 1_760_180_000_000, SessionState.RECOVERED, 12),
                    session("a", 1_760_170_000_000, SessionState.FINISHED, 18),
                    SessionSummary(
                        "i",
                        File("i"),
                        SessionInfo(
                            1_760_160_000_000,
                            track = "Tor Poznań",
                            imported = ImportOrigin("RaceChrono CSV", "session_20251011_1042.csv"),
                        ),
                        SessionState.FINISHED,
                        21 * 60_000_000L,
                    ),
                ),
                exports = mapOf("a" to ExportState.Done),
                onExport = { _, _ -> },
                transfer = SessionTransferState(
                    outgoing = mapOf("b" to TransferState("Preparing…", 0.42f, running = true)),
                    incoming = TransferState("Imported the session of 11 Oct 10:42. Added track Tor Poznań."),
                ),
                withVideo = setOf("a"),
                goPro = GoProCardState(
                    sessions = mapOf(
                        "a" to SessionGoPro(listOf(SessionCamera("g1", clips = GoProClips(2, "by GPS ±0.05 s", true)))),
                        "b" to SessionGoPro(listOf(SessionCamera("g1", onCamera = true))),
                    ),
                    listed = listOf(
                        ListedGoPro("g1", "Helmet HERO13", download = true),
                        ListedGoPro("g2", "Rear HERO7", download = false),
                    ),
                ),
            )
        }
    }
}
