package com.flappedear.betterdriver.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Marks a screen that still waits for its approved design. */
@Composable
fun PlaceholderBanner(modifier: Modifier = Modifier) {
    Text(
        text = "Placeholder: design pending approval",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
}

/** A titled placeholder screen with optional content below the title. */
@Composable
fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        PlaceholderBanner()
        content()
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() {
    BetterDriverTheme { PlaceholderScreen("Preview") }
}
