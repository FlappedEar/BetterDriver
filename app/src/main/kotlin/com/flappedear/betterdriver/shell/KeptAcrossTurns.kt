package com.flappedear.betterdriver.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** What screens hand over to themselves while the activity is made again, by key. */
class KeptAcrossTurns : ViewModel() {
    internal val values = HashMap<String, Any>()
}

/**
 * [init]'s value, kept while the activity is made again for a configuration change (the tablet
 * turned, FBBD-323), so a screen does not read and analyse its data again. Left any other way the
 * value goes: the screen opened again later reads its data fresh, as before.
 */
@Composable
fun <T : Any> rememberAcrossTurns(key: String, init: () -> T): T {
    val kept = viewModel<KeptAcrossTurns>()
    val activity = LocalActivity.current
    val value = remember(key) {
        @Suppress("UNCHECKED_CAST")
        kept.values.remove(key) as T? ?: init()
    }
    DisposableEffect(key, value) {
        onDispose { if (activity?.isChangingConfigurations == true) kept.values[key] = value }
    }
    return value
}
