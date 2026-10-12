package com.flappedear.betterdriver.recording

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.live.GnssCue
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.gnssCue
import com.flappedear.betterdriver.recorder.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private const val Tag = "GnssSound"
private const val ToneVolume = 80
private const val LostToneMs = 400
private const val BackToneMs = 200

/**
 * A short sound when [gnss] goes from OK to lost, and another when it comes back (FBBD-64). Only
 * while [enabled] (Settings › Recording, off by default). It plays here in the UI process, on the
 * notification stream, so the recorder never waits on audio; the banner shows the state either way.
 */
@Composable
fun GnssSound(gnss: GnssState?, enabled: Boolean) {
    var tones by remember { mutableStateOf<ToneGenerator?>(null) }
    // Made off the main thread while the sound is on, released when it is turned off or the screen goes.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val made = withContext(Dispatchers.Default + NonCancellable) { newToneGenerator() } ?: return@LaunchedEffect
        try {
            tones = made
            awaitCancellation()
        } finally {
            tones = null
            made.release()
        }
    }
    var before by remember { mutableStateOf(gnss) }
    LaunchedEffect(gnss) {
        val cue = gnssCue(before, gnss)
        before = gnss
        if (enabled && cue != null) {
            when (cue) {
                GnssCue.Lost -> tones?.startTone(ToneGenerator.TONE_PROP_NACK, LostToneMs)
                GnssCue.Back -> tones?.startTone(ToneGenerator.TONE_PROP_ACK, BackToneMs)
            }
        }
    }
}

/** Null when the phone has no tone generator free; the banner still shows. */
@Suppress("TooGenericExceptionCaught") // ToneGenerator throws RuntimeException when its audio track fails.
private fun newToneGenerator(): ToneGenerator? = try {
    ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneVolume)
} catch (e: RuntimeException) {
    DebugLog.w(Tag, "no tone generator", e)
    null
}
