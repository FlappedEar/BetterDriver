package com.flappedear.betterdriver.debug

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private fun mb(bytes: Long?) = bytes?.let { String.format(Locale.ROOT, "%.1f MB", it / BytesPerMb) }

/**
 * Settings › Diagnostics (FBBD-146, FBBD-183): the app's one log, kept across sessions and restarts.
 * "Share log" sends it as one file through the share sheet; after a drive that is all Arek does,
 * every hardware check is worked out from it. "Clear log" is the only thing that deletes it, after a
 * confirmation that shows its size. Sizes, the zip and the delete run off the main thread (FBBD-41).
 */
@Composable
fun DebugLogRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<Long?>(null) }
    var changes by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy, changes) { size = withContext(Dispatchers.IO) { DebugLogShare.logBytes(context) } }
    ListRow("Share log", if (busy) "Preparing…" else mb(size) ?: "…", divider = false, onClick = {
        if (!busy) {
            busy = true
            problem = null
            scope.launch {
                val zip = try {
                    withContext(Dispatchers.IO) { DebugLogShare.prepare(context) }
                } catch (e: IOException) {
                    DebugLog.w("DebugLogs", "could not prepare the log", e)
                    problem = "Could not prepare the log: ${e.message}"
                    null
                }
                busy = false
                zip?.let { DebugLogShare.share(context, it) }
            }
        }
    })
    ListRow("Clear log", onClick = { if (!busy) confirmClear = true })
    DimText("Kept until you clear it. After a drive, share it once: the app's checks are worked out from it.")
    problem?.let { DimText(it) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the log?") },
            text = { Text("This deletes ${mb(size) ?: "the log"} of log, with the records of every drive in it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { AppLog.clear(context) }
                        changes++
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
