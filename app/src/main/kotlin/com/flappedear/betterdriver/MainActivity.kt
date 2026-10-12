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
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.sharing.Backups
import com.flappedear.betterdriver.sharing.Imports

class MainActivity : ComponentActivity() {
    private lateinit var recorder: RecorderClient

    /** The file last opened with the app, kept in the saved state while its import runs. */
    private var importing: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restore the app was stopped in finishes before anything reads the data (FBBD-156).
        Backups.finishPending(this)
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
            importing?.let(::startImport)
        }
        // UI-17 option A: the app is always dark, so the system bars use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val recording = recorder.state.recording
            // The screen stays on for the whole outing, on every screen, not only the track screen.
            LaunchedEffect(recording) {
                if (recording) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            BetterDriverTheme(darkTheme = true) {
                BetterDriverApp(recorder = recorder, sessions = sessions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
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
        if (imports.state?.running != true && imports.question == null) importing = uri
        imports.import(uri, fromOutside = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (Imports.get(this).state?.running == true) importing?.let { outState.putParcelable(KeyImporting, it) }
    }

    override fun onStart() {
        super.onStart()
        recorder.bind()
    }

    override fun onStop() {
        recorder.unbind()
        super.onStop()
    }

    private companion object {
        const val KeyImporting = "importing"
    }
}
