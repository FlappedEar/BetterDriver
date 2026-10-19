package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.io.IOException

/** A rendered clip without its leading and trailing silence; null if it is missing, damaged or silent. */
private fun readClip(f: File): Pcm? = try {
    Wav.read(f.readBytes())?.trimmed()?.takeIf { it.samples.isNotEmpty() }
} catch (e: IOException) {
    DebugLog.w("CueClips", "clip ${f.name} not read", e)
    null
}

/**
 * The pre-rendered cue clips (FBBD-48): every word group and number the coach can say
 * ([CueClips.vocabulary]), rendered once with text-to-speech to WAV files in the app's cache and held
 * in memory, so a phrase is joined from clips and plays at once. Clips already rendered by the same
 * voice are reused, so only the first coached session after an install or a voice change renders.
 * Rendering uses a text-to-speech instance of its own, so the live voice can speak meanwhile. Until
 * the clips are ready, or if rendering fails, [phrase] gives null and the live voice is used. All work
 * runs on [handler]'s thread.
 */
internal class CueClipBank(context: Context, private val handler: Handler) {
    private val app = context.applicationContext

    @Volatile
    private var clips: Map<String, Pcm>? = null

    // Handler thread only.
    private var started = false
    private var speech = Speech()

    /** Counts renders, so a late callback of a replaced render is ignored. */
    private var generation = 0
    private var renderer: TextToSpeech? = null
    private var dir: File? = null
    private val pending = mutableSetOf<String>()
    private var startedAt = 0L
    private var rendered = 0

    private val timeout = Runnable { fail("rendering took over ${RenderTimeoutMillis / MillisPerSecond} s") }

    /** Starts rendering or loading the clips, once per voice, speed and pitch ([speech], FBBD-240). */
    fun prepare(speech: Speech) {
        handler.post {
            if (started && speech == this.speech) return@post
            if (started) reset()
            started = true
            this.speech = speech
            generation++
            startedAt = SystemClock.elapsedRealtime()
            DebugLog.i(Tag, "preparing ${CueClips.vocabulary.size} cue clips")
            renderer = TextToSpeech(app) { status -> handler.post { onRendererReady(status == TextToSpeech.SUCCESS) } }
            handler.postDelayed(timeout, RenderTimeoutMillis)
        }
    }

    /** [text] joined from clips, or null if the clips are not ready or one of its parts has none. */
    fun phrase(text: String): Pcm? {
        val c = clips ?: return null
        val parts = CueClips.parts(text)
        val pcms = parts.mapNotNull { p -> c[p.key]?.let { it to p.pauseAfterMillis } }
        return if (parts.isNotEmpty() && pcms.size == parts.size) Pcm.join(pcms) else null
    }

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
        if (ok && applySpeech(tts, speech).english) render(tts) else fail("no English voice to render with")
    }

    private fun render(tts: TextToSpeech) {
        val root = File(app.cacheDir, ClipsDir)
        // One folder per voice and vocabulary, so a voice change renders afresh; older folders go.
        val voice = "${tts.voice?.name}|${speech.rate}|${speech.pitch}"
        val signature = "${tts.defaultEngine}|$voice|${CueClips.vocabulary}|$ClipFormat"
        val d = File(root, Integer.toHexString(signature.hashCode()))
        root.listFiles()?.filter { it != d }?.forEach { it.deleteRecursively() }
        d.mkdirs()
        dir = d
        pending += CueClips.vocabulary.filterNot { File(d, CueClips.fileName(it)).isFile }
        rendered = pending.size
        if (pending.isEmpty()) return load(d)
        tts.setOnUtteranceProgressListener(Progress())
        val refused = pending.toList().filter { key ->
            tts.synthesizeToFile(key, Bundle(), partFile(d, key), "$generation:$key") != TextToSpeech.SUCCESS
        }
        if (refused.isNotEmpty()) fail("text-to-speech refused to render ${refused.size} clips")
    }

    private fun partFile(d: File, key: String) = File(d, CueClips.fileName(key) + ".${Process.myPid()}.part")

    private fun onClip(key: String, ok: Boolean) {
        val d = dir?.takeIf { key in pending } ?: return
        when {
            !ok || !partFile(d, key).renameTo(File(d, CueClips.fileName(key))) -> fail("clip “$key” not rendered")
            pending.remove(key) && pending.isEmpty() -> load(d)
        }
    }

    private fun load(d: File) {
        val loaded = CueClips.vocabulary
            .mapNotNull { key -> readClip(File(d, CueClips.fileName(key)))?.let { key to it } }
            .toMap()
        val bad = CueClips.vocabulary - loaded.keys
        if (bad.isNotEmpty()) {
            // Damaged files are rendered again next time.
            bad.forEach { File(d, CueClips.fileName(it)).delete() }
            fail("${bad.size} clips unreadable")
            return
        }
        clips = loaded
        val ms = SystemClock.elapsedRealtime() - startedAt
        DebugLog.i(Tag, "cue clips ready in $ms ms: ${loaded.size} clips, $rendered rendered, the rest from cache")
        finish()
    }

    private fun fail(why: String) {
        if (clips == null) DebugLog.w(Tag, "pre-rendered voice unavailable, using live text-to-speech: $why")
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
        const val Tag = "CueClips"
        const val ClipsDir = "coach-clips"

        /** Bumped when the clips' format or rendering changes, so old ones are rendered again. */
        const val ClipFormat = 1
        const val MillisPerSecond = 1_000L
        const val RenderTimeoutMillis = 120_000L
    }
}
