package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.steering.SteerAdvice
import java.util.concurrent.ConcurrentHashMap

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
 *
 * Each language has clips of its own (FBBD-245): the words of its phrases ([PhraseBook]) and the
 * number parts it says them with ([PhraseBook.numberKeys]).
 */
object CueClips {
    /** Numbers rendered as clips: 0 to 99, and "00" to "09" for a lap time's seconds. */
    const val MaxNumber = 99

    /** Between two clips of one phrase, and where the phrase has a comma. */
    const val WordGapMillis = 30
    const val CommaPauseMillis = 140

    /**
     * [text] as clips, in order. Numbers are clips of their own; the words between them are one clip, so
     * they keep their natural flow; a comma becomes a longer pause. Numbers are split as [book] says them.
     */
    fun parts(text: String, book: PhraseBook = Phrases): List<ClipPart> {
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
                    book.numberKeys(word).forEach { out += ClipPart(it, WordGapMillis) }
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

    /** Every English clip the coach can need ([vocabulary] of [Phrases]). */
    val vocabulary: Set<String> by lazy { vocabulary(Phrases) }

    private val vocabularies = ConcurrentHashMap<PhraseBook, Set<String>>()

    /**
     * Every clip the coach can need in [book]'s language: the fixed words of each kind of phrase, the
     * numbers 0 to 99, "00" to "09" for a lap time's seconds, and the parts of 100 to 999.
     */
    fun vocabulary(book: PhraseBook): Set<String> = vocabularies.getOrPut(book) {
        val corner = Corner(1, 0.0, 0.0, 0.0, 0.0, Turn.Straight)
        val samples = listOf(
            // With the target (FBBD-242).
            book.corner(corner, Advice.BrakeLater(SampleNumber, at = SampleNumber)),
            book.corner(corner, Advice.BrakeEarlier(SampleNumber, at = SampleNumber)),
            book.corner(corner, Advice.CarrySpeed(SampleNumber, goalKmh = SampleNumber)),
            // Without it, as logged before FBBD-242.
            book.corner(corner, Advice.BrakeLater(SampleNumber)),
            // A language whose word for metres follows the number ("1 metr", "2 metry", "5 metrów").
            book.corner(corner, Advice.BrakeLater(1)),
            book.corner(corner, Advice.BrakeLater(2)),
            book.corner(corner, Advice.BrakeEarlier()),
            book.corner(corner, Advice.CarrySpeed(SampleNumber)),
            // Said without the number below High confidence (FBBD-177).
            book.corner(corner, Advice.BrakeLater(SampleNumber, precise = false)),
            book.corner(corner, Advice.CarrySpeed(SampleNumber, precise = false)),
            // The line and the throttle (FBBD-307, UI-168 B, UI-169 C), both ways.
            book.corner(corner, Advice.Line(SampleNumber), Advice.Line(-SampleNumber)),
            book.corner(corner, Advice.Throttle(SampleNumber, afterApex = SampleNumber)),
            book.corner(corner, Advice.Throttle(SampleNumber, afterApex = -SampleNumber)),
            book.corner(corner, Advice.Throttle(SampleNumber, afterApex = 0)),
            book.corner(corner, Advice.Throttle(SampleNumber, precise = false), Advice.Throttle(-SampleNumber, false)),
            // The hands on the FR-S (FBBD-321, round 54).
            book.corner(corner, Advice.Steer(SteerAdvice.Less), Advice.Steer(SteerAdvice.More)),
            book.corner(corner, Advice.Steer(SteerAdvice.Smoother), Advice.Steer(SteerAdvice.Oversteer)),
            book.corner(corner, Advice.Steer(SteerAdvice.OpenEarlier(SampleNumber)), Advice.Throttle(SampleNumber)),
            book.corner(corner, Advice.Steer(SteerAdvice.OpenEarlier(SampleNumber), precise = false)),
            // The out-lap plan (UI-174 A), of one corner and of several.
            book.plan(listOf(1)),
            book.plan((1..SamplePlanCorners).toList()),
            book.praise(corner, Praise.Worked),
            book.praise(corner, Praise.BestYet),
            book.lapTime(SampleLapMillis, best = true),
            book.lapTime(SampleLapMillis, best = false),
            book.lapTime(SampleShortLapMillis, best = false),
        ) + (1..SampleCorners).map {
            // A language that names corners by word ("Trójka") has a clip per name.
            book.praise(Corner(it, 0.0, 0.0, 0.0, 0.0, Turn.Straight), Praise.Worked)
        }
        val words = samples.flatMap { parts(it, book) }.map { it.key }.filterNot { it.all(Char::isDigit) }
        val numbers = (0..MaxNumber).map(Int::toString) + (0..<PaddedNumbers).map { "0$it" }
        val big = (MaxNumber + 1..MaxSpokenNumber).flatMap { book.numberKeys(it.toString()) }
        (words + numbers + big).toSortedSet()
    }

    /** Whether every part of [text] has a clip in [book]'s language, so it can be said without live text-to-speech. */
    fun covers(text: String, book: PhraseBook = Phrases): Boolean =
        parts(text, book).let { p -> p.isNotEmpty() && p.all { it.key in vocabulary(book) } }

    /** A file name for [key]'s clip: lower case, letters and digits, anything else an underscore. */
    fun fileName(key: String): String =
        key.map { if (it.isLetterOrDigit()) it.lowercaseChar() else '_' }.joinToString("") + ".wav"

    private const val SampleNumber = 10

    /** The corners of a sample out-lap plan: as many as the plan says (UI-174 A). */
    private const val SamplePlanCorners = 3
    private const val SampleLapMillis = 92_400L
    private const val SampleShortLapMillis = 58_300L
    private const val SampleCorners = 30
    private const val PaddedNumbers = 10
    private const val Hundred = 100
    private const val MaxSpokenNumber = 999
    private const val HundredWord = "hundred"
    private const val OhWord = "oh"
}
