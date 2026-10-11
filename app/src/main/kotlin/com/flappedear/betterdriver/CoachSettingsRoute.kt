package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.shell.CoachSettingsScreen

/** Settings › App › Spoken coach (FBBD-98, UI-64 B): saved as it changes, read at the next session start. */
internal fun NavGraphBuilder.coachSettings(nav: NavHostController, padding: PaddingValues) {
    composable(Routes.CoachSettings) {
        val context = LocalContext.current
        val coach = remember(context) { CoachPrefs.get(context) }
        Box(Modifier.padding(padding)) {
            CoachSettingsScreen(coach.settings, coach::update, onBack = { nav.popBackStack() })
        }
    }
}
