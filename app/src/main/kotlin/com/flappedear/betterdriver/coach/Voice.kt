package com.flappedear.betterdriver.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android text-to-speech for cues (FBBD-98), to whatever audio output is connected: a helmet headset
 * or the car over Bluetooth, or the phone speaker. Played as navigation guidance. Text-to-speech does
 * not take audio focus itself, so each phrase asks for transient focus that lets music duck under it,
 * and gives it back once the last phrase has been said. A new phrase replaces one still being said.
 * [ready] is false until the engine has started, and stays false if there is no English voice.
 */
internal class Voice(context: Context) {
    @Volatile
    var ready = false
        private set

    private val ids = AtomicInteger()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audio: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && chooseLanguage()
    }

    init {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) = ended(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = ended(utteranceId)

                override fun onError(utteranceId: String, errorCode: Int) = ended(utteranceId)

                override fun onStop(utteranceId: String, interrupted: Boolean) = ended(utteranceId)
            },
        )
    }

    private fun chooseLanguage(): Boolean = listOf(Locale.UK, Locale.US, Locale.ENGLISH).any { locale ->
        tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    /** False when the phrase could not be queued (no voice yet, or the engine refused it). */
    fun say(text: String): Boolean {
        if (!ready) return false
        audio?.requestAudioFocus(focus)
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId(ids.incrementAndGet())) ==
            TextToSpeech.SUCCESS
        if (!queued) audio?.abandonAudioFocusRequest(focus)
        return queued
    }

    /** A phrase ended; music comes back up only when it was the latest one (a newer one replaced it otherwise). */
    private fun ended(utteranceId: String) {
        if (utteranceId == utteranceId(ids.get())) audio?.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        tts.shutdown()
        audio?.abandonAudioFocusRequest(focus)
    }

    private fun utteranceId(n: Int) = "cue-$n"
}
