package com.flappedear.betterdriver.coach

import java.util.Locale

/**
 * A text-to-speech voice as the engine lists it (FBBD-240): its [name], its language as a BCP 47
 * [languageTag] ("en-GB"), whether it needs the internet ([needsNetwork]) and whether its data is on
 * the phone ([installed]).
 */
data class TtsVoice(val name: String, val languageTag: String, val needsNetwork: Boolean, val installed: Boolean)

/**
 * Which voices the coach may use (FBBD-240). Only voices of the coach's language (English, or Polish,
 * FBBD-245) that are on the phone and need no internet: an online voice would wait for the network
 * before each cue, at a track often without signal, and the cue's start delay is kept as low as it
 * is (FBBD-47).
 */
object CoachVoices {
    /** Accents in the order they are offered; any other of the language follows, by language tag. */
    private val AccentOrder = listOf("en-GB", "en-US", "en-AU", "en-IN", "pl-PL")

    /** The usable voices of [language], by accent, then by name, so the list and each voice's number stay put. */
    fun usable(all: List<TtsVoice>, language: CoachLanguage = CoachLanguage.English): List<TtsVoice> = all
        .filter { speaks(it.languageTag, language) && !it.needsNetwork && it.installed }
        .distinctBy { it.name }
        .sortedWith(compareBy({ accentRank(it.languageTag) }, { it.languageTag.lowercase() }, { it.name }))

    /** The voice named [name] if it is still usable in [language]; null means the engine's own voice of it. */
    fun choose(name: String?, all: List<TtsVoice>, language: CoachLanguage = CoachLanguage.English): TtsVoice? =
        name?.let { n -> usable(all, language).firstOrNull { it.name == n } }

    /** [voice]'s number among the usable voices of its accent, from 1; null if it is not usable. */
    fun number(voice: TtsVoice, all: List<TtsVoice>): Int? = usable(all, languageOf(voice.languageTag))
        .filter { it.languageTag.replace('_', '-').equals(voice.languageTag.replace('_', '-'), ignoreCase = true) }
        .indexOfFirst { it.name == voice.name }
        .takeIf { it >= 0 }
        ?.plus(1)

    /** The usable voices of [language] grouped by accent, in [usable]'s order, for the voice page (UI-90 A). */
    fun byAccent(
        all: List<TtsVoice>,
        language: CoachLanguage = CoachLanguage.English,
    ): List<Pair<String, List<TtsVoice>>> = usable(all, language)
        .groupBy { it.languageTag.replace('_', '-').lowercase() }
        .map { (_, voices) -> accent(voices.first().languageTag) to voices }

    /** "English (United Kingdom)": an accent's heading on the voice page. */
    fun accent(tag: String): String = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.ENGLISH)

    /**
     * "English (UK) · Voice 3" or "Polski · Voice 2" (UI-96 A): the voice on the Spoken coach page;
     * null if it is not usable.
     */
    fun label(voice: TtsVoice, all: List<TtsVoice>): String? = number(voice, all)?.let { n ->
        val language = languageOf(voice.languageTag)
        val name = if (language == CoachLanguage.English) englishName(voice.languageTag) else languageName(language)
        "$name · Voice $n"
    }

    /** "English" or "Polski": each language by its own name, as the Language row shows it (UI-96 A). */
    fun languageName(language: CoachLanguage): String = when (language) {
        CoachLanguage.English -> "English"
        CoachLanguage.Polish -> "Polski"
    }

    /** The coach language a voice's [tag] speaks; English for any other. */
    fun languageOf(tag: String): CoachLanguage =
        CoachLanguage.entries.firstOrNull { speaks(tag, it) } ?: CoachLanguage.English

    private fun speaks(tag: String, language: CoachLanguage) =
        tag.lowercase().replace('_', '-').let { it == language.tag || it.startsWith(language.tag + "-") }

    private fun accentRank(tag: String): Int {
        val i = AccentOrder.indexOfFirst { it.equals(tag.replace('_', '-'), ignoreCase = true) }
        return if (i >= 0) i else AccentOrder.size
    }
}

/** "English (UK)", "English (Australia)", "English": an English voice's accent on the Spoken coach page. */
private fun englishName(tag: String): String {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    val country = when (locale.country) {
        "GB" -> "UK"
        "US" -> "US"
        "" -> null
        else -> locale.getDisplayCountry(Locale.ENGLISH)
    }
    val name = locale.getDisplayLanguage(Locale.ENGLISH)
    return if (country != null) "$name ($country)" else name
}
