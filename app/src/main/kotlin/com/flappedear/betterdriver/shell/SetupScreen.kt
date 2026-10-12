package com.flappedear.betterdriver.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.setup.Setup

/**
 * First run (UI-13 option B): one checklist page, steps in any order. Only permissions are needed
 * to finish. The other steps show what Arek has set up (FBBD-83); without a GNSS receiver home
 * says so and START stays greyed out (UI-37 B).
 */
@Suppress("LongParameterList") // One callback per step.
@Composable
fun SetupScreen(
    setup: Setup,
    onAddReceiver: () -> Unit,
    onCameras: () -> Unit,
    onCar: () -> Unit,
    onAddCar: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = setup.active()
    val permissions = permissionsStep()
    val steps = listOf(
        permissions,
        SetupStep(
            "GNSS receiver",
            active.gnss?.name ?: "Needed for lap timing",
            done = active.gnss != null,
            action = if (active.gnss == null) "Add" else null,
            onAction = onAddReceiver,
        ),
        SetupStep(
            "Cameras",
            active.cameras.joinToString(", ") { it.name }.ifEmpty { "None (optional)" },
            done = active.cameras.isNotEmpty(),
            action = "Set up",
            onAction = onCameras,
        ),
        SetupStep(
            "Car",
            active.car?.name ?: "Optional",
            done = active.car != null,
            action = if (setup.cars.isEmpty()) "Add" else "Choose",
            onAction = if (setup.cars.isEmpty()) onAddCar else onCar,
        ),
    )
    SetupChecklist(steps, canFinish = permissions.done, onDone = onDone, modifier = modifier)
}

/**
 * The permissions step: done once the required ones are granted. After the dialog has answered,
 * each denied permission says what will not work (FBBD-35).
 */
@Composable
private fun permissionsStep(): SetupStep {
    val context = LocalContext.current
    val required = remember { requiredPermissions() }
    val all = remember { required + optionalPermissions() }
    var granted by remember { mutableStateOf(context.allGranted(required)) }
    var allGranted by remember { mutableStateOf(context.allGranted(all)) }
    // Set when Android stops showing the dialog after repeated denials; then only system settings can grant.
    var blocked by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(emptyList<PermissionGroup>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.allGranted(required)
        allGranted = context.allGranted(all)
        asked = true
        denied = context.deniedPermissionGroups(all)
        // Optional ones count too: otherwise "Allow" would open no dialog for a camera denied for good.
        blocked = !allGranted && context.findActivity()?.let { it.permanentlyDenied(all) } == true
    }
    // Picks up grants made in system settings.
    LifecycleResumeEffect(Unit) {
        granted = context.allGranted(required)
        allGranted = context.allGranted(all)
        if (asked) denied = context.deniedPermissionGroups(all)
        if (allGranted) blocked = false
        onPauseOrDispose {}
    }
    return SetupStep(
        "Permissions",
        "Location and nearby devices · camera, mic and notifications optional",
        done = granted,
        action = when {
            blocked -> "Settings"
            allGranted -> null
            else -> "Allow"
        },
        notes = denied.map { it.deniedLine() },
        onAction = { if (blocked) context.openAppSettings() else launcher.launch(all.toTypedArray()) },
    )
}

@Composable
internal fun SetupChecklist(
    steps: List<SetupStep>,
    canFinish: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Set up")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DimText("${steps.count { it.done }} of ${steps.size} done. You can start without the optional steps.")
            steps.forEach { SetupRow(it) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onDone, enabled = canFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
            if (!canFinish) {
                DimText("Allow location and nearby devices first.", Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun SetupRow(step: SetupStep) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (step.done) DeviceState.Ready else DeviceState.Off)
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            DimText(step.detail)
            step.notes.forEach { DimText(it, Modifier.padding(top = 4.dp), color = TrackTheme.colors.warning) }
        }
        if (step.action != null) {
            FilledTonalButton(onClick = step.onAction) { Text(step.action) }
        }
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun SetupChecklistPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            SetupChecklist(
                steps = listOf(
                    SetupStep(
                        "Permissions",
                        "Location and nearby devices · camera, mic and notifications optional",
                        true,
                        "Settings",
                        notes = listOf(PermissionGroup.Camera.deniedLine(), PermissionGroup.Microphone.deniedLine()),
                    ) {},
                    SetupStep("GNSS receiver", "Needed for lap timing", false, "Add") {},
                    SetupStep("Cameras", "Phone camera", true, "Set up") {},
                    SetupStep("Car", "Optional", false, "Add") {},
                ),
                canFinish = true,
                onDone = {},
            )
        }
    }
}
