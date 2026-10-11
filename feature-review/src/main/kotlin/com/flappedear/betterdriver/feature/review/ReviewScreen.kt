package com.flappedear.betterdriver.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.PlaceholderScreen

/**
 * Placeholder for session review. On a wide screen (the tablet) it shows two
 * panes side by side; the real layout comes from the approved UI design.
 */
@Composable
fun ReviewScreen(wide: Boolean, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Review", modifier = modifier) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LapList()
                Text("Video and charts", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LapList()
        }
    }
}

@Composable
private fun LapList() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Laps", style = MaterialTheme.typography.titleMedium)
        Text("Lap 1  1:44.902")
        Text("Lap 2  1:42.317")
        Text("Lap 3  1:41.894")
    }
}

@Preview(widthDp = 900)
@Composable
private fun ReviewScreenWidePreview() {
    BetterDriverTheme { ReviewScreen(wide = true) }
}
