package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme

/**
 * Device pairing (UI-14 option B): one big card for the closest device, with
 * "Show all devices" as a fallback. The app scans while this screen is open
 * and passes the strongest one; with none in range it shows searching. Used
 * for the RaceBox and, with its own [title] and [hint], the GoPro HERO11.
 * [busy] shows while pairing takes a moment; [error] says why it failed.
 */
@Composable
fun PairScreen(
    candidate: PairCandidate?,
    onConnect: (PairCandidate) -> Unit,
    onShowAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Pair RaceBox",
    hint: String = "Power the RaceBox Micro. It shows up here.",
    busy: Boolean = false,
    error: String? = null,
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar(title, onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DimText(hint)
            if (candidate != null) BestMatchCard(candidate, busy, onConnect) else SearchingCard()
            if (error != null) DimText(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onShowAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Not yours? Show all devices")
            }
        }
    }
}

@Composable
private fun BestMatchCard(candidate: PairCandidate, busy: Boolean, onConnect: (PairCandidate) -> Unit) {
    ShellCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SectionLabel("Found")
            Text(candidate.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DimText(candidate.serial)
                SignalBars(candidate.bars)
                DimText(if (candidate.bars >= 3) "strong signal" else "weak signal")
            }
        }
        Button(
            onClick = { onConnect(candidate) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (busy) "Pairing…" else "Connect", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SearchingCard() {
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Searching…", style = MaterialTheme.typography.bodyLarge)
        }
        DimText("Make sure Bluetooth is on.")
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            val color = if (i < bars) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            Box(Modifier.width(3.dp).height(h.dp).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

@Preview(widthDp = 411, heightDp = 600)
@Composable
private fun PairScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            PairScreen(PairCandidate("RaceBox Micro", "3241", 4), onConnect = {}, onShowAll = {}, onBack = {})
        }
    }
}

@Preview(widthDp = 411, heightDp = 600)
@Composable
private fun PairScreenSearchingPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            PairScreen(null, onConnect = {}, onShowAll = {}, onBack = {})
        }
    }
}
