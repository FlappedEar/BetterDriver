package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.Advice
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CornerPass
import com.flappedear.betterdriver.coach.Cue
import com.flappedear.betterdriver.coach.CueKind
import com.flappedear.betterdriver.coach.CueReview
import com.flappedear.betterdriver.coach.ReviewedCue
import com.flappedear.betterdriver.coach.confidenceText
import com.flappedear.betterdriver.coach.outcome
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.recorder.Confidence

/**
 * The cue review of one session (FBBD-28, FBBD-99): every cue the coach said, in order, and for a
 * corner cue what the next pass through that corner did, marked Better or Not yet. One column on
 * the phone, two or three on the tablet ([layout], FBBD-40). [review] is null while the session is read.
 */
@Composable
fun CueReviewScreen(
    title: String,
    review: CueReview?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    layout: LayoutSize = LayoutSize.Phone,
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Coach review", onBack = onBack)
        when {
            review == null -> Box(Modifier.padding(horizontal = 16.dp)) { ShellCard { DimText("Reading session…") } }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(cueColumns(layout)),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { ReviewHeader(title, review) }
                // No key by cue number: a restarted coach numbers its cues from 1 again.
                items(review.cues) { CueCard(it) }
            }
        }
    }
}

@Composable
private fun ReviewHeader(title: String, review: CueReview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        DimText(review.summary)
        if (review.problems > 0) {
            val n = review.problems
            DimText(if (n == 1) "1 cue not said (too late or no voice)" else "$n cues not said (too late or no voice)")
        }
    }
}

@Composable
private fun CueCard(item: ReviewedCue) {
    val c = TrackTheme.colors
    val cue = item.cue
    val (verdict, color) = when {
        cue.kind == CueKind.LapTime -> "Lap time" to Color.Unspecified
        cue.kind == CueKind.Praise -> "Praise" to c.gain
        item.result == null -> "No pass after" to Color.Unspecified
        item.result?.improved == true -> "Better" to c.gain
        else -> "Not yet" to c.loss
    }
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val where = listOfNotNull("Lap ${cue.lap}", cue.heardTurn?.let { "Turn $it" }).joinToString(" · ")
            Text(
                where,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            DimText(verdict, color = color)
        }
        Text("“${cue.text}”", style = MaterialTheme.typography.bodyLarge)
        cue.confidenceText()?.let { DimText(it) }
        item.outcome()?.let { DimText(it) }
    }
}

@Preview(widthDp = 411, heightDp = 700)
@Composable
private fun CueReviewPreview() {
    val before = CornerPass(2, 4, 480.0, 560.0, 152.0, 70.0, 8_800_000)
    val target = before.copy(brakeAt = 495.0, minKmh = 71.0, timeMicros = 8_500_000)
    val corner = Cue(
        5, 0, 5, CueKind.Corner, "Turn 2, brake at 65, 15 later", 2, Advice.BrakeLater(15, at = 65), before, target,
    )
    val after = before.copy(lap = 5, brakeAt = 491.0, timeMicros = 8_620_000)
    val review = CueReview(
        listOf(
            ReviewedCue(Cue(4, 0, 4, CueKind.LapTime, "1 32 point 4"), null),
            ReviewedCue(
                corner,
                CoachEvents.Result(5, after, improved = true, gainMicros = 180_000, confidence = Confidence.HIGH),
            ),
        ),
        problems = 0,
    )
    BetterDriverTheme { CueReviewScreen("Sat 11 Oct, 14:02", review, onBack = {}) }
}
