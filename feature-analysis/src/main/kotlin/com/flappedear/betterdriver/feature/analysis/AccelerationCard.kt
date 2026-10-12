package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Acceleration runs (FBBD-154): the quickest run of each kind found in the session, with its trap
 * speed (distance runs) and peak forward g. A kind with more runs opens to list them, with when
 * in the session each started. A run with a GPS issue is marked and says which.
 */
@Composable
internal fun AccelerationCard(ui: AccelerationUi, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Acceleration")
        for (kind in ui.kinds) {
            var open by rememberSaveable(kind.label) { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = kind.others.isNotEmpty()) { open = !open }
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kind.label, color = c.text, modifier = Modifier.weight(1f))
                    Dim(detail(kind.best), Modifier.padding(end = 12.dp))
                    RunTime(kind.best, best = true)
                }
                val more = kind.count - 1
                if (kind.best.note.isNotEmpty()) Dim(kind.best.note, color = c.warning)
                if (more > 0) Dim(if (open) "Every run" else "$more more ${if (more == 1) "run" else "runs"}")
                if (open) {
                    for (run in kind.others) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dim("at ${clock(run.atSeconds)}", Modifier.weight(1f))
                            Dim(detail(run), Modifier.padding(end = 12.dp))
                            RunTime(run, best = false)
                        }
                        if (run.note.isNotEmpty()) Dim(run.note, color = c.warning)
                    }
                    val unlisted = more - kind.others.size
                    if (unlisted > 0) Dim("and $unlisted slower")
                }
            }
        }
        Dim("Standing starts without rollout. GPS speed.")
    }
}

@Composable
private fun RunTime(run: RunRow, best: Boolean) {
    val c = TrackTheme.colors
    Text(
        // "≈" marks a time the GPS data makes less certain.
        (if (run.note.isEmpty()) "" else "≈") + "${seconds(run.seconds)} s",
        color = when {
            run.note.isNotEmpty() -> c.warning
            best -> c.best
            else -> c.text
        },
        fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.End,
        modifier = Modifier.width(TimeWidth),
    )
}

private val TimeWidth = 72.dp

/** Trap speed and peak g, as far as the run has them. */
private fun detail(run: RunRow) = listOfNotNull(
    run.trapKmh?.let { "${it.roundToInt()} km/h" },
    run.peakG?.let { String.format(Locale.ROOT, "%.2f g", it) },
).joinToString(" · ")

private const val SecondsPerMinute = 60
private const val MinutesPerHour = 60

/** Session time as m:ss, or h:mm:ss. */
private fun clock(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val s = total % SecondsPerMinute
    val minutes = total / SecondsPerMinute
    val h = minutes / MinutesPerHour
    val m = minutes % MinutesPerHour
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/**
 * The analysis's message when it has no laps to show (a road drive, say), then the session's
 * G-forces and the Acceleration card when the session has runs: they need no laps. Then its data
 * quality (FBBD-183), once worked out.
 */
@Composable
internal fun WithAcceleration(
    acceleration: AccelerationUi?,
    quality: QualityUi? = null,
    gForces: GgTrace? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
        gForces?.let { GForcesCard(it, width) }
        acceleration?.let { AccelerationCard(it, width) }
        quality?.let { QualityCard(it, width) }
    }
}

internal val PreviewAcceleration = AccelerationUi(
    listOf(
        RunKindRow(
            "0–100 km/h",
            RunRow(4.21, 312.0, null, 0.94, ""),
            listOf(RunRow(4.48, 905.0, null, 0.88, ""), RunRow(5.02, 1410.0, null, 0.81, "GPS gap")),
            count = 3,
        ),
        RunKindRow("100–200 km/h", RunRow(9.87, 318.0, null, 0.61, ""), emptyList(), count = 1),
        RunKindRow("0–60 mph", RunRow(4.02, 312.0, null, 0.94, ""), emptyList(), count = 1),
        RunKindRow("1/8 mile", RunRow(7.66, 312.0, 151.6, 0.94, "GPS under 10 Hz"), emptyList(), count = 1),
    ),
)

@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun AccelerationCardPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            AccelerationCard(PreviewAcceleration)
        }
    }
}
