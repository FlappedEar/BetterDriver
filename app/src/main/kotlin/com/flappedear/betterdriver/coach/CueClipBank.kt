package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

/** A rendered clip's bytes; null if it is missing or cannot be read. */
private fun readFile(f: File): ByteArray? = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null

/** Where a clip is rendered, named with this process id, before it is renamed into place. */
private fun partFile(d: File, key: String) = File(d, CueClips.fileName(key) + ".${Process.myPid()}.part")

/**
 * The pre-rendered cue clips (FBBD-48): every word group and number the coach can say in its language
 * ([CueClips.vocabulary], FBBD-245), rendered once with text-to-speech to WAV files in the app's cache
 * and held in memory, so a phrase is joined from clips and plays at once. Clips already rendered by
 * the same engine, voice and language are reused, so only the first coached session after an install
 * or a change of any of them renders.
 * Rendering uses a text-to-speech instance of its own, so the live voice can speak meanwhile. Until
 * the clips are ready, or if rendering fails, [phrase] gives null and the live voice is used. All work
 * runs on [handler]'s thread.
 */
internal class CueClipBank(context: Context, private val handler: Handler) {
    private val app = context.applicationContext

    @Volatile
    private var clips: CueClipSet? = null

    // Handler thread only.
    private var started = false
    private var speech = Speech()
    private var book: PhraseBook = Phrases

    private var language = CoachLanguage.English

    /** Counts renders, so a late callback of a replaced render is ignored. */
    private var generation = 0
    private var renderer: TextToSpeech? = null
    private var dir: File? = null
    private val pending = mutableSetOf<String>()
    private var startedAt = 0L
    private var rendered = 0

    private val timeout = Runnable { fail("rendering took over ${RenderTimeoutMillis / MillisPerSecond} s") }

    /**
     * Starts rendering or loading the clips, once per engine, voice, speed and pitch ([speech],
     * FBBD-240) and [language] (FBBD-245).
     */
    fun prepare(speech: Speech, language: CoachLanguage) {
        handler.post {
            if (started && speech == this.speech && language == this.language) return@post
            if (started) reset()
            started = true
            this.speech = speech
            this.language = language
            book = PhraseBook.of(language)
            generation++
            startedAt = SystemClock.elapsedRealtime()
            CueClipLog.preparing(language)
            val gen = generation
            renderer = TextToSpeech(
                app,
                { status -> handler.post { if (gen == generation) onRendererReady(status == TextToSpeech.SUCCESS) } },
                speech.engine,
            )
            handler.postDelayed(timeout, RenderTimeoutMillis)
        }
    }

    /**
     * [text] in [language] joined from clips, or null if the clips are not ready, are of another
     * language, or one of its parts has none.
     */
    fun phrase(text: String, language: CoachLanguage): Pcm? = clips?.phrase(text, language)

    fun shutdown() {
        handler.post {
            handler.removeCallbacks(timeout)
            renderer?.shutdown()
            renderer = null
        }
    }

    /** Drops the clips and stops a render of another voice or speed. */
    private fun reset() {
        clips = null
        pending.clear()
        dir = null
        finish()
    }

    private fun onRendererReady(ok: Boolean) {
        val tts = renderer ?: return
        val spoken = ok && applySpeech(tts, speech, language).spoken
        if (spoken) render(tts) else fail("no ${language.tag} voice to render with")
    }

    private fun render(tts: TextToSpeech) {
        val root = File(app.cacheDir, ClipsDir)
        // One folder per voice and vocabulary, so a voice change renders afresh; older folders go.
        val voice = "${tts.voice?.name}|${speech.rate}|${speech.pitch}"
        val vocabulary = CueClips.vocabulary(book)
        val signature = "${speech.engine ?: tts.defaultEngine}|$voice|$vocabulary|$ClipFormat"
        val d = File(root, Integer.toHexString(signature.hashCode()))
        root.listFiles()?.filter { it != d }?.forEach { it.deleteRecursively() }
        d.mkdirs()
        dir = d
        pending += vocabulary.filterNot { File(d, CueClips.fileName(it)).isFile }
        rendered = pending.size
        if (pending.isEmpty()) return load(d)
        tts.setOnUtteranceProgressListener(Progress())
        val refused = pending.toList().filter { key ->
            tts.synthesizeToFile(key, Bundle(), partFile(d, key), "$generation:$key") != TextToSpeech.SUCCESS
        }
        if (refused.isNotEmpty()) fail("text-to-speech refused to render ${refused.size} clips")
    }

    private fun onClip(key: String, ok: Boolean) {
        val d = dir?.takeIf { key in pending } ?: return
        when {
            !ok || !partFile(d, key).renameTo(File(d, CueClips.fileName(key))) -> fail("clip “$key” not rendered")
            pending.remove(key) && pending.isEmpty() -> load(d)
        }
    }

    private fun load(d: File) {
        // Damaged files are deleted, so they are rendered again next time.
        val loaded = CueClipSet.load(
            language,
            read = { key -> readFile(File(d, CueClips.fileName(key))) },
            delete = { key -> File(d, CueClips.fileName(key)).delete() },
        )
        val set = loaded.set ?: return fail("${loaded.bad} clips unreadable")
        clips = set
        CueClipLog.ready(SystemClock.elapsedRealtime() - startedAt, set.size, rendered)
        finish()
    }

    private fun fail(why: String) {
        if (clips == null) CueClipLog.unavailable(why)
        pending.clear()
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        renderer?.shutdown()
        renderer = null
    }

    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = clip(utteranceId, ok = true)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = clip(utteranceId, ok = false)

        override fun onError(utteranceId: String?, errorCode: Int) = clip(utteranceId, ok = false)

        private fun clip(id: String?, ok: Boolean) {
            val gen = id?.substringBefore(':')?.toIntOrNull() ?: return
            val key = id.substringAfter(':')
            handler.post { if (gen == generation) onClip(key, ok) }
        }
    }

    private companion object {
        const val ClipsDir = "coach-clips"

        /** Bumped when the clips' format or rendering changes, so old ones are rendered again. */
        const val ClipFormat = 1
        const val MillisPerSecond = 1_000L
        const val RenderTimeoutMillis = 120_000L
    }
}
