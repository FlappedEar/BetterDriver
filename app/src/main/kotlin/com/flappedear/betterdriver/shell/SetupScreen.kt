package com.flappedear.betterdriver.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.core.ui.BetterDriverTheme

/** Permissions needed to log a session: location (GNSS) and nearby devices (RaceBox). */
internal fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

/** Asked for with the required ones, but setup can finish without them (phone video, mic, notifications). */
internal fun optionalPermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Context.allGranted(permissions: List<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * First run (UI-13 option B): one checklist page, steps in any order. Only
 * permissions are needed to start; the other steps are optional for now
 * because pairing is not built yet.
 */
@Composable
fun SetupScreen(
    prefs: AppPrefs,
    onPair: () -> Unit,
    onCameras: () -> Unit,
    onCar: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val required = remember { requiredPermissions() }
    val all = remember { required + optionalPermissions() }
    var granted by remember { mutableStateOf(context.allGranted(required)) }
    var allGranted by remember { mutableStateOf(context.allGranted(all)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.allGranted(required)
        allGranted = context.allGranted(all)
    }
    val steps = listOf(
        SetupStep(
            "Permissions",
            "Location and nearby devices · camera, mic and notifications optional",
            done = granted,
            action = if (allGranted) null else "Allow",
            onAction = { launcher.launch(all.toTypedArray()) },
        ),
        SetupStep("RaceBox Micro", "Needed for lap timing", done = false, action = "Pair", onAction = onPair),
        SetupStep(
            "Cameras",
            if (prefs.phoneCamera) "Phone camera on · GoPro optional" else "Phone camera, GoPro (optional)",
            done = prefs.phoneCamera,
            action = "Set up",
            onAction = onCameras,
        ),
        SetupStep("Car", "${prefs.car} (optional)", done = true, action = "Choose", onAction = onCar),
    )
    SetupChecklist(steps, canFinish = granted, onDone = onDone, modifier = modifier)
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
                    SetupStep("Permissions", "Location, nearby devices, camera", true, null) {},
                    SetupStep("RaceBox Micro", "Needed for lap timing", false, "Pair") {},
                    SetupStep("Cameras", "Phone camera, GoPro (optional)", false, "Set up") {},
                    SetupStep("Car", "2023 Toyota Corolla (optional)", true, "Choose") {},
                ),
                canFinish = true,
                onDone = {},
            )
        }
    }
}
