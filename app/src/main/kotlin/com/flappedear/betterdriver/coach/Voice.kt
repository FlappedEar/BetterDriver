package com.flappedear.betterdriver.coach

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android text-to-speech for cues (FBBD-98), to whatever audio output is connected: a helmet headset
 * or the car over Bluetooth, or the phone speaker. Played as navigation guidance, so music ducks under
 * it. A new phrase replaces one still being said. [ready] is false until the engine has started, and
 * stays false if there is no English voice.
 */
internal class Voice(context: Context) {
    @Volatile
    var ready = false
        private set

    private val ids = AtomicInteger()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && chooseLanguage()
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    private fun chooseLanguage(): Boolean = listOf(Locale.UK, Locale.US, Locale.ENGLISH).any { locale ->
        tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    /** False when the phrase could not be queued (no voice yet, or the engine refused it). */
    fun say(text: String): Boolean =
        ready && tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue-${ids.incrementAndGet()}") == TextToSpeech.SUCCESS

    fun shutdown() = tts.shutdown()
}
