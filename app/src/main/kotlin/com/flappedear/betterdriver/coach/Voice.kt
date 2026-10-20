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

/** Logs the time from asking for a phrase to its sound starting, for [path] "live" or "pre-rendered". */
private fun logStart(path: String, askedAt: Long, startedAt: Long) =
    DebugLog.i("Voice", "$path voice start ${(startedAt - askedAt) / NanosPerMilli} ms")

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

/**
 * Picks the engine's own voice of [language] on [tts]: British, American or any English; Polish
 * (FBBD-245). False if it has none.
 */
internal fun chooseLanguage(tts: TextToSpeech, language: CoachLanguage): Boolean {
    val locales = when (language) {
        CoachLanguage.English -> listOf(Locale.UK, Locale.US, Locale.ENGLISH)
        CoachLanguage.Polish -> listOf(Locale.forLanguageTag("pl-PL"), Locale.forLanguageTag("pl"))
    }
    return locales.any { tts.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
}

/** The speech engines on the phone (FBBD-245, UI-101 A): package to name, as Android lists them. */
@Suppress("TooGenericExceptionCaught") // An engine list that fails only leaves the default engine.
internal fun ttsEngines(tts: TextToSpeech): List<Pair<String, String>> = try {
    tts.engines.orEmpty().map { it.name to it.label }
} catch (e: RuntimeException) {
    DebugLog.w("Voice", "text-to-speech engines not listed", e)
    emptyList()
}

/** The engine's voices as the coach sees them (FBBD-240); empty if the engine will not list them. */
@Suppress("TooGenericExceptionCaught") // An engine that fails to list voices only leaves the default.
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
 * Sets [speech] on [tts] (FBBD-240): its voice if still usable in [language], else the engine's own
 * voice of [language] (FBBD-245), and its speed and pitch. Gives the voice in use, for the log; false
 * in [Applied.spoken] if the engine has no voice of [language].
 */
internal fun applySpeech(tts: TextToSpeech, speech: Speech, language: CoachLanguage): Applied {
    val all = ttsVoices(tts)
    val wanted = CoachVoices.choose(speech.voiceName, all.map { it.info() }, language)
    val set = wanted != null && all.first { it.name == wanted.name }.let { tts.setVoice(it) == TextToSpeech.SUCCESS }
    val spoken = set || chooseLanguage(tts, language)
    tts.setSpeechRate(speech.rate)
    tts.setPitch(speech.pitch)
    val name = runCatching { tts.voice?.name }.getOrNull()
    if (speech.voiceName != null && !set) DebugLog.w("Voice", "voice ${speech.voiceName} not usable, using $name")
    return Applied(spoken, name)
}

internal data class Applied(val spoken: Boolean, val voiceName: String?)

/**
 * The coach's voice (FBBD-98), on [AudioOut]: to whatever audio output is connected, a helmet headset or
 * the car over Bluetooth, or the phone speaker, as navigation guidance with transient focus so music
 * ducks under it (FBBD-47). A new phrase replaces one still being said. [ready] is false until the
 * engine has started, and stays false if it has no voice of the coach's language (FBBD-245). The
 * engine (the phone's default, or the one picked, UI-101 A) is started when the voice is made and
 * again when another is picked, and warmed with a moment of silence, so the first cue does not wait.
 *
 * Two ways to say a phrase (FBBD-48), set by [use]: live text-to-speech, or clips rendered at coach
 * start and joined, which start sooner. A phrase without clips (they are still rendering, rendering
 * failed, or a number has none) is said live. The time from asking to the first audio is logged for
 * both, to compare them.
 */
@Suppress("TooManyFunctions") // Saying, sampling, and what the engine offers for the settings pages.
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
    private var language = CoachLanguage.English

    @Volatile
    private var engineReady = false

    /** When the last phrase was asked for, [SystemClock.elapsedRealtimeNanos]; for [speaking]. */
    @Volatile
    private var lastAskedAt = 0L

    /**
     * Whether a phrase is still being said (FBBD-253): the coach waits for its end instead of cutting
     * it off with the next. An engine that never reports a phrase's end holds the coach for at most
     * [MaxPhraseNanos].
     */
    val speaking: Boolean
        get() = out.busy && SystemClock.elapsedRealtimeNanos() - lastAskedAt < MaxPhraseNanos

    /** Counts engines started, so a late start of a replaced engine is ignored. Voice thread only. */
    private var engineGeneration = 0

    /** Live phrases not yet ended, by utterance id: when each was asked for (elapsed-realtime nanos). */
    private val asked = ConcurrentHashMap<String, Asked>()

    private class Asked(val at: Long, val onStart: Started, val onEnd: Ended, val focus: Boolean) {
        /** When the engine's first audio of it was ready, System.nanoTime. */
        @Volatile
        var readyNanos: Long? = null

        @Volatile
        var started = false
    }

    private val app = context.applicationContext

    /** The engine package [tts] was started with; null for the phone's default. */
    @Volatile
    private var engine: String? = null

    @Volatile
    private var tts: TextToSpeech = start(null)

    /** Starts the speech engine [engine] (null: the phone's default); it is ready when it says so. */
    private fun start(engine: String?): TextToSpeech {
        val gen = ++engineGeneration
        return TextToSpeech(app, { status -> handler.post { if (gen == engineGeneration) onInit(status) } }, engine)
            .apply {
                setAudioAttributes(AudioOut.Attributes)
                setOnUtteranceProgressListener(Progress())
            }
    }

    private fun onInit(status: Int) {
        engineReady = status == TextToSpeech.SUCCESS
        if (!engineReady) DebugLog.w(Tag, "speech engine ${engine ?: "default"} did not start")
        applyNow()
    }

    /**
     * On the voice thread: starts the wanted engine if another is running (UI-101 A), sets the wanted
     * voice and speed on the started engine, then warms it: opens the audio path with a moment of
     * silence, so the next phrase does not wait for it.
     */
    private fun applyNow() {
        if (speech.engine != engine) {
            engine = speech.engine
            engineReady = false
            ready = false
            tts.shutdown()
            tts = start(engine)
            return
        }
        if (!engineReady) return
        val applied = applySpeech(tts, speech, language)
        ready = applied.spoken
        val engineName = engine ?: runCatching { tts.defaultEngine }.getOrNull()
        val said = "${applied.voiceName ?: "none"} (${language.tag}, engine $engineName)"
        DebugLog.i(Tag, "voice $said, speed ${speech.rate}, pitch ${speech.pitch}")
        if (ready) tts.playSilentUtterance(WarmMillis, TextToSpeech.QUEUE_ADD, WarmId)
    }

    /**
     * How phrases are said from now on, set at each coached session's start: live or pre-rendered,
     * which voice at what speed ([speech], FBBD-240), and in which [language] (FBBD-245). Pre-rendered
     * starts rendering the clips if they are not yet rendered with that voice, speed and language.
     * Warms the audio path again.
     */
    fun use(voice: VoiceMode, speech: Speech = Speech(), language: CoachLanguage = CoachLanguage.English) {
        if (voice != mode) DebugLog.i(Tag, "voice: $voice")
        mode = voice
        this.speech = speech
        this.language = language
        if (voice == VoiceMode.PreRendered) clips.prepare(speech, language)
        handler.post { applyNow() }
    }

    /**
     * Says [text] live in [speech] and [language], which are used from now on (FBBD-240): the samples
     * on the voice page and the Test voice button, so they sound as the cues will. Said once the
     * engine is ready, so a sample right after an engine change is not lost.
     */
    fun sample(speech: Speech, language: CoachLanguage, text: String) {
        mode = VoiceMode.Live
        this.speech = speech
        this.language = language
        handler.post {
            applyNow()
            sayWhenReady(text, SampleWaitMillis)
        }
    }

    private fun sayWhenReady(text: String, waitMillis: Long) {
        when {
            ready -> say(text)
            waitMillis > 0 ->
                handler.postDelayed({ sayWhenReady(text, waitMillis - SamplePollMillis) }, SamplePollMillis)
            else -> DebugLog.w(Tag, "sample not said: no voice of ${language.tag}")
        }
    }

    /** The voices of [engine] (null: the phone's default), null until that engine has started (FBBD-240). */
    fun voices(engine: String?): List<TtsVoice>? = if (started(engine)) ttsVoices(tts).map { it.info() } else null

    /** The phone's speech engines, package to name (UI-101 A); null until [engine] has started. */
    fun engines(engine: String?): List<Pair<String, String>>? = if (started(engine)) ttsEngines(tts) else null

    /** The phone's default engine's package, null until [engine] has started. */
    fun defaultEngine(engine: String?): String? =
        if (started(engine)) runCatching { tts.defaultEngine }.getOrNull() else null

    private fun started(engine: String?) = engineReady && engine == this.engine

    /**
     * False when the phrase could not be played or queued (no voice yet, or the engine refused it).
     * [onStart] runs on the voice thread when its sound starts, and [onEnd] when a phrase that
     * started ends, for the validation report (FBBD-183).
     */
    fun say(text: String, onEnd: Ended = Ended { _, _ -> }, onStart: Started = Started { }): Boolean {
        val id = "cue-${ids.incrementAndGet()}"
        val at = SystemClock.elapsedRealtimeNanos()
        lastAskedAt = at
        val clip = if (mode == VoiceMode.PreRendered) clips.phrase(text, language) else null
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
        const val SampleWaitMillis = 5_000L
        const val SamplePollMillis = 100L
        const val MaxPhraseNanos = 10_000_000_000L
    }
}
