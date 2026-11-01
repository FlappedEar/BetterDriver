package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine

/**
 * The pre-rendered cue clips of one language, loaded (FBBD-48): a phrase is joined from them. Shared by
 * both apps, which only render the clips with their own text-to-speech and play the joined sound.
 */
class CueClipSet(val language: CoachLanguage, private val clips: Map<String, Pcm>) {
    val size: Int get() = clips.size

    /**
     * [text] in [language] joined from clips, or null if the clips are of another language or one of
     * its parts has none; then it is said live.
     */
    fun phrase(text: String, language: CoachLanguage = this.language): Pcm? {
        if (language != this.language) return null
        val parts = CueClips.parts(text, PhraseBook.of(language))
        val pcms = parts.mapNotNull { p -> clips[p.key]?.let { it to p.pauseAfterMillis } }
        return if (parts.isNotEmpty() && pcms.size == parts.size) Pcm.join(pcms) else null
    }

    companion object {
        /**
         * Every clip of [language]'s vocabulary ([CueClips.vocabulary]), read with [read] (a WAV file's
         * bytes, null if there is none) without its leading and trailing silence. Clips missing, damaged
         * or silent are given to [delete], so they are rendered again next time, and the set is null.
         */
        fun load(language: CoachLanguage, read: (key: String) -> ByteArray?, delete: (key: String) -> Unit): Loaded {
            val vocabulary = CueClips.vocabulary(PhraseBook.of(language))
            val loaded = vocabulary.mapNotNull { key ->
                read(key)?.let(Wav::read)?.trimmed()?.takeIf { it.samples.isNotEmpty() }?.let { key to it }
            }.toMap()
            val bad = vocabulary - loaded.keys
            bad.forEach(delete)
            return Loaded(CueClipSet(language, loaded).takeIf { bad.isEmpty() }, bad.size)
        }

        /**
         * The name of the folder the clips of one voice are kept in: a change of any part of
         * [voice] (engine, voice, speed, pitch), of the vocabulary or of [format] renders them afresh.
         */
        fun folderName(voice: String, language: CoachLanguage, format: Int): String =
            "$voice|${CueClips.vocabulary(PhraseBook.of(language))}|$format".hashCode().toUInt().toString(Hex)

        private const val Hex = 16
    }

    /** What [load] found: the set when every clip read, and how many did not. */
    class Loaded(val set: CueClipSet?, val bad: Int)
}

/** The CueClips lines of the continuous log, the same on both apps (FBBD-48). */
object CueClipLog {
    const val Tag = "CueClips"

    fun preparing(language: CoachLanguage) {
        val n = CueClips.vocabulary(PhraseBook.of(language)).size
        logLine(CoreLog.LevelInfo, Tag, "preparing $n cue clips (${language.tag})")
    }

    fun ready(millis: Long, clips: Int, rendered: Int) = logLine(
        CoreLog.LevelInfo, Tag, "cue clips ready in $millis ms: $clips clips, $rendered rendered, the rest from cache",
    )

    fun unavailable(why: String) =
        logLine(CoreLog.LevelWarn, Tag, "pre-rendered voice unavailable, using live text-to-speech: $why")
}
