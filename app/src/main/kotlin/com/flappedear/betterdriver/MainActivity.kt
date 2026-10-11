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
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.sharing.Imports

class MainActivity : ComponentActivity() {
    private lateinit var recorder: RecorderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recovers interrupted sessions the first time the app starts in this process (FBBD-71).
        val sessions = Sessions.get(this)
        recorder = RecorderClient.get(this)
        // A session bundle or track file opened from Quick Share or a file manager (FBBD-23, FBBD-80).
        if (savedInstanceState == null) importFrom(intent)
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
        Imports.get(this).import(uri, fromOutside = true)
    }

    override fun onStart() {
        super.onStart()
        recorder.bind()
    }

    override fun onStop() {
        recorder.unbind()
        super.onStop()
    }
}
