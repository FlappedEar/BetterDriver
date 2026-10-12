package com.flappedear.betterdriver.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme

/**
 * Settings › App › About and credits (FBBD-79): the version, then the data, map tiles, fonts, ported
 * code and libraries the app uses, each with its licence. A credit with a link opens it in the
 * browser. One column, kept to a readable width on a tablet.
 */
@Composable
fun AboutScreen(version: String, onBack: () -> Unit) {
    val sections = remember { aboutSections() }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("About and credits", onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShellCard {
                    Text(
                        "BetterDriver",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    DimText("Version $version")
                }
                sections.forEach { section ->
                    SectionLabel(section.title, Modifier.padding(top = 8.dp))
                    ShellCard {
                        section.credits.forEachIndexed { i, credit ->
                            CreditRow(credit, divider = i > 0) { url -> context.openLink(url) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditRow(credit: Credit, divider: Boolean, onOpen: (String) -> Unit) {
    Column {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(
            Modifier
                .fillMaxWidth()
                .let { m -> credit.url?.let { url -> m.clickable { onOpen(url) } } ?: m }
                .padding(vertical = 10.dp),
        ) {
            Text(credit.name, style = MaterialTheme.typography.bodyLarge)
            DimText(credit.detail)
            DimText(credit.license, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Opens [url] in the browser; nothing happens on a phone without one. */
private fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun AboutScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AboutScreen(version = "0.3.1 (build 412)", onBack = {})
        }
    }
}
