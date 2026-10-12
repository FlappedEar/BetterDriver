package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/**
 * Next session (FBBD-20, UI-69): up to three areas to look at, each the measured observation and,
 * apart from it, what may be worth comparing. Never advice on how to drive. Tapping an area
 * compares its lap with the fastest; an area at a corner can be picked for the spoken coach's next
 * session there (FBBD-148). Each area shows its confidence (FBBD-177); corners left out because
 * what they showed rests on one lap or on weak positions are named, with why.
 */
@Composable
internal fun Focus(ui: AnalysisUi, onCompare: (Int) -> Unit, onCoachPick: (String) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Next session · look at")
        for (area in ui.focus) {
            val index = area.compareIndex
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = index != null) { index?.let(onCompare) }
                    .padding(vertical = 6.dp),
            ) {
                Text("${area.name} · ${area.kind}", color = c.text, fontWeight = FontWeight.SemiBold)
                Text(area.observation, color = c.text, style = MaterialTheme.typography.bodyMedium)
                ConfidenceNote(area.confidence)
                Dim(area.hypothesis)
                area.compareNumber?.let { Dim("Tap to compare lap $it", color = c.cold) }
                CoachPick(ui, area.cornerId, onCoachPick)
            }
        }
        for (left in ui.withheld) Dim("Not listed: ${left.name} · ${left.why}")
        Dim("Observations from this session's laps, not driving advice.")
    }
}
