package com.flappedear.betterdriver.coach

/**
 * A text-to-speech voice as the engine lists it (FBBD-240): its [name], its language as a BCP 47
 * [languageTag] ("en-GB"), whether it needs the internet ([needsNetwork]) and whether its data is on
 * the phone ([installed]).
 */
data class TtsVoice(val name: String, val languageTag: String, val needsNetwork: Boolean, val installed: Boolean)

/**
 * Which voices the coach may use (FBBD-240). Only English voices that are on the phone and need no
 * internet: an online voice would wait for the network before each cue, at a track often without
 * signal, and the cue's start delay is kept as low as it is (FBBD-47).
 */
object CoachVoices {
    /** Accents in the order they are offered; any other English follows, by language tag. */
    private val AccentOrder = listOf("en-GB", "en-US", "en-AU", "en-IN")

    /** The usable voices, by accent, then by name, so the list and each voice's number stay put. */
    fun usable(all: List<TtsVoice>): List<TtsVoice> = all
        .filter { isEnglish(it.languageTag) && !it.needsNetwork && it.installed }
        .distinctBy { it.name }
        .sortedWith(compareBy({ accentRank(it.languageTag) }, { it.languageTag.lowercase() }, { it.name }))

    /** The voice named [name] if it is still usable; null means the engine's own English voice. */
    fun choose(name: String?, all: List<TtsVoice>): TtsVoice? =
        name?.let { n -> usable(all).firstOrNull { it.name == n } }

    /** [voice]'s number among the usable voices of its accent, from 1; null if it is not usable. */
    fun number(voice: TtsVoice, all: List<TtsVoice>): Int? = usable(all)
        .filter { it.languageTag.equals(voice.languageTag, ignoreCase = true) }
        .indexOfFirst { it.name == voice.name }
        .takeIf { it >= 0 }
        ?.plus(1)

    private fun isEnglish(tag: String) =
        tag.lowercase().let { it == "en" || it.startsWith("en-") || it.startsWith("en_") }

    private fun accentRank(tag: String): Int {
        val i = AccentOrder.indexOfFirst { it.equals(tag.replace('_', '-'), ignoreCase = true) }
        return if (i >= 0) i else AccentOrder.size
    }
}
