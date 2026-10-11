package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.feature.review.ReviewScreen
import com.flappedear.betterdriver.review.ReviewLaps
import com.flappedear.betterdriver.shell.ShellIcons

/** Lap review with each lap's video (FBBD-24), opened from Sessions. Sessions are read off the main thread. */
@Composable
internal fun ReviewRoute(nav: NavHostController, padding: PaddingValues) {
    val context = LocalContext.current
    val laps = remember(context) { ReviewLaps.get(context) }
    LaunchedEffect(Unit) { laps.load() }
    Box(Modifier.padding(padding)) {
        ReviewScreen(laps.state, ShellIcons.Back, onBack = { nav.popBackStack() })
    }
}
