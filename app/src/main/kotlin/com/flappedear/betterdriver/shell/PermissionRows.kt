package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flappedear.betterdriver.core.ui.TrackTheme

/**
 * Settings › App › Permissions (FBBD-35): one line per permission that is off, saying what will not
 * work, and the row opens this app's page in system settings, where they can be turned on.
 * Checked again on resume, so a change made there shows on return.
 */
@Composable
internal fun PermissionRows() {
    val context = LocalContext.current
    val asked = remember { requiredPermissions() + optionalPermissions() }
    var denied by remember { mutableStateOf(context.deniedPermissionGroups(asked)) }
    LifecycleResumeEffect(Unit) {
        denied = context.deniedPermissionGroups(asked)
        onPauseOrDispose {}
    }
    ListRow(
        "Permissions",
        if (denied.isEmpty()) "All allowed" else "${denied.size} off",
        onClick = { context.openAppSettings() },
    )
    DeniedPermissionLines(denied)
}

/** What stops working for each denied permission group, in the warning colour. Nothing when none are. */
@Composable
internal fun DeniedPermissionLines(denied: List<PermissionGroup>, modifier: Modifier = Modifier) {
    if (denied.isEmpty()) return
    Column(modifier.padding(bottom = 8.dp)) {
        denied.forEach { DimText(it.deniedLine(), Modifier.padding(top = 4.dp), color = TrackTheme.colors.warning) }
    }
}
