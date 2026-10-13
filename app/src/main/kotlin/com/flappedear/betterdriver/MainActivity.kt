package com.flappedear.betterdriver

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.debug.StartupLog
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.UiChecks
import com.flappedear.betterdriver.sharing.Backups
import com.flappedear.betterdriver.sharing.Imports
import com.flappedear.betterdriver.shell.RestoreUnfinished

class MainActivity : ComponentActivity() {
    private lateinit var recorder: RecorderClient

    /** The file last opened with the app, kept in the saved state while its import runs. */
    private var importing: Uri? = null

    /**
     * The same file, kept on disk until its import ends: a saved state taken before the end must not
     * start the import again after Android ends the process.
     */
    private val pendingImport by lazy {
        DebugGuards.allowDisk { getSharedPreferences(PendingImportFile, MODE_PRIVATE) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restore the app was stopped in finishes before anything reads the data (FBBD-156). If it
        // cannot, nothing else starts: the data is part restored until it is finished. On the main
        // thread on purpose, so StrictMode is told not to report it (FBBD-41).
        if (!DebugGuards.allowDisk { Backups.finishPending(this) }) {
            setContent { BetterDriverTheme(darkTheme = true) { RestoreUnfinished(onRetry = ::recreate) } }
            // This is the process's first screen too: its cold start is timed like any other (FBBD-41).
            StartupLog.onScreenCreated(this, restored = savedInstanceState != null)
            return
        }
        // Recovers interrupted sessions the first time the app starts in this process (FBBD-71).
        val sessions = Sessions.get(this)
        recorder = RecorderClient.get(this)
        // A session bundle or track file opened from Quick Share or a file manager (FBBD-23, FBBD-80).
        // Android can end the process while a long import runs in the background and later bring the
        // activity back; the import then starts again. In the same process Imports ignores the repeat.
        if (savedInstanceState == null) {
            importFrom(intent)
        } else {
            importing = BundleCompat.getParcelable(savedInstanceState, KeyImporting, Uri::class.java)
                ?.takeIf { DebugGuards.allowDisk { pendingImport.getString(KeyImporting, null) } == it.toString() }
            importing?.let(::startImport)
        }
        // UI-17 option A: the app is always dark, so the system bars use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val importRunning = Imports.get(this).state?.running == true
            LaunchedEffect(importRunning) {
                if (!importRunning) DebugGuards.allowDisk { pendingImport.edit().remove(KeyImporting).commit() }
            }
            val recording = recorder.state.recording
            // The screen stays on for the whole outing, on every screen, not only the track screen.
            LaunchedEffect(recording) {
                if (recording) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Debug builds log slow frames once a minute while recording (FBBD-41).
                DebugGuards.trackJank(this@MainActivity, recording)
                // Every build puts the frames in the drive's log every 30 s while recording (FBBD-62).
                UiChecks.trackFrames(this@MainActivity, recording)
            }
            BetterDriverTheme(darkTheme = true) {
                // A restore that failed half way in this process blocks the app until it is finished.
                if (Backups.unfinished) {
                    RestoreUnfinished(onRetry = { DebugGuards.allowDisk { Backups.finishPending(this@MainActivity) } })
                } else {
                    BetterDriverApp(recorder = recorder, sessions = sessions)
                }
            }
        }
        // The first frame of a cold start goes in the drive's log (FBBD-41).
        StartupLog.onScreenCreated(this, restored = savedInstanceState != null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::recorder.isInitialized) importFrom(intent)
    }

    private fun importFrom(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        startImport(uri)
    }

    private fun startImport(uri: Uri) {
        val imports = Imports.get(this)
        // Imports ignores a file opened while another one is being imported.
        if (imports.state?.running != true && imports.question == null) {
            importing = uri
            DebugGuards.allowDisk { pendingImport.edit().putString(KeyImporting, uri.toString()).commit() }
        }
        imports.import(uri, fromOutside = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::recorder.isInitialized && Imports.get(this).state?.running == true) {
            importing?.let { outState.putParcelable(KeyImporting, it) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::recorder.isInitialized) recorder.bind(this)
    }

    override fun onStop() {
        if (::recorder.isInitialized) recorder.unbind(this)
        super.onStop()
    }

    private companion object {
        const val KeyImporting = "importing"
        const val PendingImportFile = "pending_import"
    }
}
