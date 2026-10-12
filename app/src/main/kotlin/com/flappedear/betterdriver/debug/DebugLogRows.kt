package com.flappedear.betterdriver.debug

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

private const val BytesPerMb = 1_000_000.0

/**
 * Settings › App › Debug logs (FBBD-146): the switch, on by default during the alpha, and a row
 * that packs the logs into a zip for the share sheet. The switch and the size are read and written
 * off the main thread (FBBD-41); the rows show the default until they are read.
 */
@Composable
fun DebugLogRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { on = withContext(Dispatchers.IO) { AppLog.enabled(context) } }
    LaunchedEffect(busy) { size = withContext(Dispatchers.IO) { DebugLogShare.logBytes(context) / BytesPerMb } }
    ListRow("Debug logs", if (on) "On" else "Off") {
        Switch(checked = on, onCheckedChange = { v ->
            on = v
            scope.launch(Dispatchers.IO) {
                try {
                    AppLog.setEnabled(context, v)
                } catch (e: IOException) {
                    DebugLog.w("DebugLogs", "could not switch the log", e)
                }
            }
        })
    }
    val label = if (busy) "Preparing…" else size?.let { String.format(Locale.ROOT, "%.1f MB", it) } ?: "…"
    ListRow("Share debug logs", label, onClick = {
        if (!busy) {
            busy = true
            problem = null
            scope.launch {
                val zip = try {
                    withContext(Dispatchers.IO) { DebugLogShare.prepare(context) }
                } catch (e: IOException) {
                    DebugLog.w("DebugLogs", "could not prepare the zip", e)
                    problem = "Could not prepare the logs: ${e.message}"
                    null
                }
                busy = false
                zip?.let { DebugLogShare.share(context, it) }
            }
        }
    })
    problem?.let { DimText(it) }
}
