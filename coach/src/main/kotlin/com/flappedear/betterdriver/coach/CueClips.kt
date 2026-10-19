package com.flappedear.betterdriver.coach

/** One piece of a phrase said from a pre-rendered clip (FBBD-48): words or a number, and the pause after it. */
data class ClipPart(val key: String, val pauseAfterMillis: Int)

/**
 * Pre-rendered cue clips (FBBD-48). Every phrase the coach says ([Phrases]) is words that never change
 * ("Turn", "brake", "metres later", "that worked", "Best lap", "point") and numbers. Each of those is rendered to a
 * clip once at coach start; a phrase is played by joining its clips, so it starts as soon as it is
 * asked for, without waiting for text-to-speech to synthesise it.
 *
 * A number from 100 to 999 (a brake point or an apex speed, FBBD-242) is said as track people say it,
 * from the clips there are: 150 as "1" "50" ("one fifty"), 105 as "1" "oh" "5", 200 as "2" "hundred".
 */
object CueClips {
    /** Numbers rendered as clips: 0 to 99, and "00" to "09" for a lap time's seconds. */
    const val MaxNumber = 99

    /** Between two clips of one phrase, and where the phrase has a comma. */
    const val WordGapMillis = 30
    const val CommaPauseMillis = 140

    /**
     * [text] as clips, in order. Numbers are clips of their own; the words between them are one clip, so
     * they keep their natural flow; a comma becomes a longer pause.
     */
    fun parts(text: String): List<ClipPart> {
        val out = mutableListOf<ClipPart>()
        val words = mutableListOf<String>()
        fun flush(pause: Int) {
            if (words.isEmpty()) return
            out += ClipPart(words.joinToString(" "), pause)
            words.clear()
        }
        for (raw in text.split(' ').filter { it.isNotEmpty() }) {
            val word = raw.trimEnd(',')
            when {
                word.isEmpty() -> Unit
                word.all(Char::isDigit) -> {
                    flush(WordGapMillis)
                    numberKeys(word).forEach { out += ClipPart(it, WordGapMillis) }
                }
                else -> words += word
            }
            if (raw.endsWith(',')) {
                flush(CommaPauseMillis)
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMillis = CommaPauseMillis)
            }
        }
        flush(0)
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMillis = 0)
        return out
    }

    /** The clips of the number [word]: itself, or for 100 to 999 its hundreds and the rest. */
    internal fun numberKeys(word: String): List<String> {
        val n = word.toIntOrNull()?.takeUnless { word.startsWith('0') }
        if (n == null || n !in (MaxNumber + 1)..MaxSpokenNumber) return listOf(word)
        val rest = n % Hundred
        val tail = when {
            rest == 0 -> listOf(HundredWord)
            rest < PaddedNumbers -> listOf(OhWord, rest.toString())
            else -> listOf(rest.toString())
        }
        return listOf((n / Hundred).toString()) + tail
    }

    /** Every clip the coach can need: the fixed words of each kind of phrase, and the numbers. */
    val vocabulary: Set<String> by lazy {
        val corner = Corner(1, 0.0, 0.0, 0.0, 0.0, Turn.Straight)
        val samples = listOf(
            // With the target (FBBD-242).
            Phrases.corner(corner, Advice.BrakeLater(SampleNumber, at = SampleNumber)),
            Phrases.corner(corner, Advice.BrakeEarlier(SampleNumber, at = SampleNumber)),
            Phrases.corner(corner, Advice.CarrySpeed(SampleNumber, goalKmh = SampleNumber)),
            // Without it, as logged before FBBD-242.
            Phrases.corner(corner, Advice.BrakeLater(SampleNumber)),
            Phrases.corner(corner, Advice.BrakeEarlier()),
            Phrases.corner(corner, Advice.CarrySpeed(SampleNumber)),
            // Said without the number below High confidence (FBBD-177).
            Phrases.corner(corner, Advice.BrakeLater(SampleNumber, precise = false)),
            Phrases.corner(corner, Advice.CarrySpeed(SampleNumber, precise = false)),
            Phrases.praise(corner, Praise.Worked),
            Phrases.praise(corner, Praise.BestYet),
            Phrases.lapTime(SampleLapMillis, best = true),
            Phrases.lapTime(SampleLapMillis, best = false),
        )
        val words = samples.flatMap(::parts).map { it.key }.filterNot { it.all(Char::isDigit) }
        val numbers = (0..MaxNumber).map(Int::toString) + (0..<PaddedNumbers).map { "0$it" }
        (words + numbers + HundredWord + OhWord).toSortedSet()
    }

    /** Whether every part of [text] has a clip, so it can be said without live text-to-speech. */
    fun covers(text: String): Boolean = parts(text).let { p -> p.isNotEmpty() && p.all { it.key in vocabulary } }

    /** A file name for [key]'s clip: lower case, letters and digits, anything else an underscore. */
    fun fileName(key: String): String =
        key.map { if (it.isLetterOrDigit()) it.lowercaseChar() else '_' }.joinToString("") + ".wav"

    private const val SampleNumber = 10
    private const val SampleLapMillis = 92_400L
    private const val PaddedNumbers = 10
    private const val Hundred = 100
    private const val MaxSpokenNumber = 999
    private const val HundredWord = "hundred"
    private const val OhWord = "oh"
}
