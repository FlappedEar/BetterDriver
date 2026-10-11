package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flappedear.betterdriver.core.ui.PlaceholderScreen
import com.flappedear.betterdriver.feature.review.ReviewScreen
import com.flappedear.betterdriver.feature.track.TrackScreen

/** App destinations. Every screen is a placeholder until its design is approved. */
object Routes {
    const val Home = "home"
    const val Track = "track"
    const val Review = "review"
    const val Settings = "settings"
}

@Composable
fun BetterDriverApp(wide: Boolean) {
    val nav = rememberNavController()
    Scaffold { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Home,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Home) {
                HomeScreen(
                    onStartSession = { nav.navigate(Routes.Track) },
                    onReview = { nav.navigate(Routes.Review) },
                    onSettings = { nav.navigate(Routes.Settings) },
                )
            }
            composable(Routes.Track) { TrackScreen(onStop = { nav.popBackStack() }) }
            composable(Routes.Review) { ReviewScreen(wide = wide) }
            composable(Routes.Settings) { SettingsScreen() }
        }
    }
}

@Composable
private fun HomeScreen(onStartSession: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit) {
    PlaceholderScreen(title = "BetterDriver") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("No sessions yet")
            Button(onClick = onStartSession) { Text("Start session") }
            OutlinedButton(onClick = onReview) { Text("Review sessions") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun SettingsScreen() {
    PlaceholderScreen(title = "Settings") {
        Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
    }
}
