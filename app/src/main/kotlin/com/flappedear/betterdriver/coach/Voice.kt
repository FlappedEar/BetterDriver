package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val NanosPerMilli = 1_000_000L

/**
 * A phrase's sound started: at [System.nanoTime] [nanoTime] (the session clock's base), said [path]
 * ("live" or "clip"), to [route] ("headset" or "speaker").
 */
internal fun interface Started {
    fun started(nanoTime: Long, path: String, route: String)
}

/** Picks a British, American or any English voice on [tts]; false if it has none. */
internal fun chooseEnglish(tts: TextToSpeech): Boolean = listOf(Locale.UK, Locale.US, Locale.ENGLISH).any { locale ->
    tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
}

/**
 * The coach's voice (FBBD-98), on [AudioOut]: to whatever audio output is connected, a helmet headset or
 * the car over Bluetooth, or the phone speaker, as navigation guidance with transient focus so music
 * ducks under it (FBBD-47). A new phrase replaces one still being said. [ready] is false until the
 * engine has started, and stays false if there is no English voice. The engine is started when the
 * voice is made and warmed with a moment of silence, so the first cue does not wait for it.
 *
 * Two ways to say a phrase (FBBD-48), set by [use]: live text-to-speech, or clips rendered at coach
 * start and joined, which start sooner. A phrase without clips (they are still rendering, rendering
 * failed, or a number has none) is said live. The time from asking to the first audio is logged for
 * both, to compare them.
 */
internal class Voice(context: Context) {
    @Volatile
    var ready = false
        private set

    private val thread = HandlerThread("voice").apply { start() }
    private val handler = Handler(thread.looper)
    private val out = AudioOut(context, handler)
    private val clips = CueClipBank(context, handler)
    private val ids = AtomicInteger()

    @Volatile
    private var mode = VoiceMode.Live

    /** Live phrases not yet started, by utterance id: when each was asked for (elapsed-realtime nanos). */
    private val asked = ConcurrentHashMap<String, Asked>()

    private class Asked(val at: Long, val onStart: Started)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        tts.setAudioAttributes(AudioOut.Attributes)
        tts.setOnUtteranceProgressListener(Progress())
    }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS && chooseEnglish(tts)
        warm()
    }

    /** Opens the audio path with a moment of silence, so the next phrase does not wait for it. */
    private fun warm() {
        if (ready) tts.playSilentUtterance(WarmMillis, TextToSpeech.QUEUE_ADD, WarmId)
    }

    /**
     * How phrases are said from now on, set at each coached session's start; pre-rendered starts
     * rendering the clips if they are not yet. Warms the audio path again.
     */
    fun use(voice: VoiceMode) {
        if (voice != mode) DebugLog.i(Tag, "voice: $voice")
        mode = voice
        if (voice == VoiceMode.PreRendered) clips.prepare()
        warm()
    }

    /**
     * False when the phrase could not be played or queued (no voice yet, or the engine refused it).
     * [onStart] runs on the voice thread when its sound starts, for the validation report (FBBD-183).
     */
    fun say(text: String, onStart: Started = Started { _, _, _ -> }): Boolean {
        val id = "cue-${ids.incrementAndGet()}"
        val at = SystemClock.elapsedRealtimeNanos()
        val clip = if (mode == VoiceMode.PreRendered) clips.phrase(text) else null
        return (clip != null && playClip(clip, id, at, onStart)) || (ready && speak(text, id, at, onStart))
    }

    fun shutdown() {
        tts.shutdown()
        clips.shutdown()
        out.shutdown()
        thread.quitSafely()
    }

    private fun playClip(clip: Pcm, id: String, at: Long, onStart: Started): Boolean {
        tts.stop()
        return out.play(clip, id) { started ->
            logStart("pre-rendered", at, started)
            onStart.started(System.nanoTime(), "clip", route())
        } != null
    }

    private fun speak(text: String, id: String, at: Long, onStart: Started): Boolean {
        out.stopClip()
        out.begin(id)
        asked[id] = Asked(at, onStart)
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!queued) {
            asked.remove(id)
            out.end(id)
        }
        return queued
    }

    private fun logStart(path: String, askedAt: Long, startedAt: Long) =
        DebugLog.i(Tag, "$path voice start ${(startedAt - askedAt) / NanosPerMilli} ms")

    /** Where the sound goes: a connected headset (Bluetooth, wired or USB), else the phone speaker. */
    private fun route() = if (out.headset() != null) "headset" else "speaker"

    /** Logs a live phrase's start, and gives the focus back when it ends, stops or fails. */
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            asked.remove(utteranceId)?.let {
                logStart("live", it.at, SystemClock.elapsedRealtimeNanos())
                it.onStart.started(System.nanoTime(), "live", route())
            }
        }

        override fun onDone(utteranceId: String?) = ended(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = ended(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)

        private fun ended(id: String?) {
            if (id == null || id == WarmId) return
            asked.remove(id)
            out.end(id)
        }
    }

    private companion object {
        const val Tag = "Voice"
        const val WarmId = "warm"
        const val WarmMillis = 50L
    }
}
