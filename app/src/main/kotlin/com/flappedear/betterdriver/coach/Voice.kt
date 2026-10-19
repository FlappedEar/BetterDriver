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
 * ("live" or "clip"), to [route] ("headset" or "speaker"). For the drive's checks (FBBD-47): when
 * its first audio was ready ([readyNanos], System.nanoTime), the output [device] type, and whether
 * audio focus was granted ([focus]).
 */
internal data class VoiceStart(
    val nanoTime: Long,
    val path: String,
    val route: String,
    val readyNanos: Long?,
    val device: String,
    val focus: Boolean,
)

internal fun interface Started {
    fun started(start: VoiceStart)
}

/** A phrase's sound ended at [System.nanoTime] [nanoTime]: "done", "stopped" (cut off) or "error". */
internal fun interface Ended {
    fun ended(nanoTime: Long, end: String)
}

/** Picks a British, American or any English voice on [tts]; false if it has none. */
internal fun chooseEnglish(tts: TextToSpeech): Boolean = listOf(Locale.UK, Locale.US, Locale.ENGLISH).any { locale ->
    tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
}

/** The engine's voices as the coach sees them (FBBD-240); empty if the engine will not list them. */
internal fun ttsVoices(tts: TextToSpeech): List<android.speech.tts.Voice> = try {
    tts.voices?.toList().orEmpty()
} catch (e: RuntimeException) {
    DebugLog.w("Voice", "text-to-speech voices not listed", e)
    emptyList()
}

internal fun android.speech.tts.Voice.info() = TtsVoice(
    name = name,
    languageTag = locale.toLanguageTag(),
    needsNetwork = isNetworkConnectionRequired,
    installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty(),
)

/**
 * Sets [speech] on [tts] (FBBD-240): its voice if still usable, else an English voice as before, and
 * its speed and pitch. Gives the voice in use, for the log; false in [Applied.english] if there is no
 * English.
 */
internal fun applySpeech(tts: TextToSpeech, speech: Speech): Applied {
    val all = ttsVoices(tts)
    val wanted = CoachVoices.choose(speech.voiceName, all.map { it.info() })
    val set = wanted != null && all.first { it.name == wanted.name }.let { tts.setVoice(it) == TextToSpeech.SUCCESS }
    val english = set || chooseEnglish(tts)
    tts.setSpeechRate(speech.rate)
    tts.setPitch(speech.pitch)
    val name = runCatching { tts.voice?.name }.getOrNull()
    if (speech.voiceName != null && !set) DebugLog.w("Voice", "voice ${speech.voiceName} not usable, using $name")
    return Applied(english, name)
}

internal data class Applied(val english: Boolean, val voiceName: String?)

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

    @Volatile
    private var speech = Speech()

    @Volatile
    private var engineReady = false

    /** Live phrases not yet ended, by utterance id: when each was asked for (elapsed-realtime nanos). */
    private val asked = ConcurrentHashMap<String, Asked>()

    private class Asked(val at: Long, val onStart: Started, val onEnd: Ended, val focus: Boolean) {
        /** When the engine's first audio of it was ready, System.nanoTime. */
        @Volatile
        var readyNanos: Long? = null

        @Volatile
        var started = false
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        tts.setAudioAttributes(AudioOut.Attributes)
        tts.setOnUtteranceProgressListener(Progress())
    }

    private fun onInit(status: Int) {
        engineReady = status == TextToSpeech.SUCCESS
        handler.post { applyNow() }
    }

    /** On the voice thread: sets the wanted voice and speed on the started engine, then warms it. */
    private fun applyNow() {
        if (!engineReady) return
        val applied = applySpeech(tts, speech)
        ready = applied.english
        DebugLog.i(Tag, "voice ${applied.voiceName ?: "none"}, speed ${speech.rate}, pitch ${speech.pitch}")
        warm()
    }

    /** Opens the audio path with a moment of silence, so the next phrase does not wait for it. */
    private fun warm() {
        if (ready) tts.playSilentUtterance(WarmMillis, TextToSpeech.QUEUE_ADD, WarmId)
    }

    /**
     * How phrases are said from now on, set at each coached session's start: live or pre-rendered,
     * and which voice at what speed ([speech], FBBD-240). Pre-rendered starts rendering the clips if
     * they are not yet rendered with that voice and speed. Warms the audio path again.
     */
    fun use(voice: VoiceMode, speech: Speech = Speech()) {
        if (voice != mode) DebugLog.i(Tag, "voice: $voice")
        mode = voice
        this.speech = speech
        if (voice == VoiceMode.PreRendered) clips.prepare(speech)
        handler.post { applyNow() }
    }

    /**
     * Says [text] live in [speech], which is used from now on (FBBD-240): the samples on the voice
     * page and the Test voice button, so they sound as the cues will.
     */
    fun sample(speech: Speech, text: String) {
        mode = VoiceMode.Live
        this.speech = speech
        handler.post {
            applyNow()
            say(text)
        }
    }

    /** The engine's voices, null until the engine has started (FBBD-240). */
    fun voices(): List<TtsVoice>? = if (engineReady) ttsVoices(tts).map { it.info() } else null

    /**
     * False when the phrase could not be played or queued (no voice yet, or the engine refused it).
     * [onStart] runs on the voice thread when its sound starts, and [onEnd] when a phrase that
     * started ends, for the validation report (FBBD-183).
     */
    fun say(text: String, onEnd: Ended = Ended { _, _ -> }, onStart: Started = Started { }): Boolean {
        val id = "cue-${ids.incrementAndGet()}"
        val at = SystemClock.elapsedRealtimeNanos()
        val clip = if (mode == VoiceMode.PreRendered) clips.phrase(text) else null
        return (clip != null && playClip(clip, id, at, onStart, onEnd)) ||
            (ready && speak(text, id, at, onStart, onEnd))
    }

    fun shutdown() {
        tts.shutdown()
        clips.shutdown()
        out.shutdown()
        thread.quitSafely()
    }

    private fun playClip(clip: Pcm, id: String, at: Long, onStart: Started, onEnd: Ended): Boolean {
        tts.stop()
        // A clip's audio is ready as soon as it is asked for.
        val ready = System.nanoTime()
        return out.play(clip, id, onEnd = { how -> onEnd.ended(System.nanoTime(), how) }) { started ->
            logStart("pre-rendered", at, started)
            onStart.started(VoiceStart(System.nanoTime(), "clip", route(), ready, out.output(), out.focusGranted))
        } != null
    }

    private fun speak(text: String, id: String, at: Long, onStart: Started, onEnd: Ended): Boolean {
        out.stopClip()
        out.begin(id)
        asked[id] = Asked(at, onStart, onEnd, out.focusGranted)
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

    /** Logs a live phrase's start and end, and gives the focus back when it ends, stops or fails. */
    private inner class Progress : UtteranceProgressListener() {
        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
            asked[utteranceId]?.let { if (it.readyNanos == null) it.readyNanos = System.nanoTime() }
        }

        override fun onStart(utteranceId: String?) {
            asked[utteranceId]?.let {
                it.started = true
                logStart("live", it.at, SystemClock.elapsedRealtimeNanos())
                val start = VoiceStart(System.nanoTime(), "live", route(), it.readyNanos, out.output(), it.focus)
                it.onStart.started(start)
            }
        }

        override fun onDone(utteranceId: String?) = ended(utteranceId, "done")

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = ended(utteranceId, "error")

        override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId, "error")

        override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId, "stopped")

        private fun ended(id: String?, how: String) {
            if (id == null || id == WarmId) return
            asked.remove(id)?.takeIf { it.started }?.onEnd?.ended(System.nanoTime(), how)
            out.end(id)
        }
    }

    private companion object {
        const val Tag = "Voice"
        const val WarmId = "warm"
        const val WarmMillis = 50L
    }
}
