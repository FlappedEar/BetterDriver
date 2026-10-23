package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the coach says in Polish (FBBD-245): the same phrases as [Phrases], with the same numbers, in
 * the same order, worded as Arek picked on the UI review page, round 24: corners 1 to 10 by their
 * number nouns, "Trójka" (UI-97 B), short phrases with the target first, then the change ("Trójka,
 * hamuj na 100, 10 później", UI-98 A), lap times as bare numbers, "1, 32, 4" (UI-99 B, his note
 * "Jeden, trzydzieści dwa, cztery"). Numbers stay digits, so text-to-speech says them in Polish and
 * the pre-rendered clips ([CueClips]) join them from parts: 150 as "100" "50" ("sto pięćdziesiąt").
 */
@Suppress("TooManyFunctions", "CyclomaticComplexMethod") // Each phrase with its few forms.
object PolishPhrases : PhraseBook {
    override fun words(a: Advice): String = when (a) {
        is Advice.BrakeLater -> when {
            !a.precise -> "hamuj trochę później"
            a.at != null -> "hamuj na ${a.at}, ${a.meters} później"
            else -> "hamuj ${a.meters} ${metres(a.meters)} później"
        }
        is Advice.BrakeEarlier -> when {
            a.precise && a.at != null && a.meters != null -> "hamuj na ${a.at}, ${a.meters} wcześniej"
            else -> "hamuj wcześniej"
        }
        is Advice.CarrySpeed -> when {
            !a.precise -> "trochę więcej prędkości"
            a.goalKmh != null -> "${a.goalKmh} w apeksie, ${a.kmh} więcej"
            else -> "${a.kmh} więcej"
        }
        // UI-168 B, UI-169 C: "Jedynka, ciaśniej w apeksie", "Jedynka, gaz 5 za apeksem".
        is Advice.Line -> if (a.tighterMeters >= 0) "ciaśniej w apeksie" else "szerzej w apeksie"
        is Advice.Throttle -> {
            val after = a.afterApex
            when {
                !a.precise || after == null -> if (a.earlierMeters >= 0) "gaz wcześniej" else "gaz później"
                after == 0 -> "gaz w apeksie"
                after > 0 -> "gaz $after za apeksem"
                else -> "gaz ${-after} przed apeksem"
            }
        }
    }

    override fun shortCorner(cue: Cue): String? {
        val a = cue.advice
        val target = if (a != null && cue.also != null) shortTarget(a) ?: words(a) else shortTarget(a)
        val heard = cue.heardTurn
        return if (target == null || heard == null) null else "${turn(heard)}, $target".takeIf { it != cue.text }
    }

    private fun shortTarget(a: Advice?): String? = when {
        a == null || !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "hamuj na ${a.at}"
        a is Advice.BrakeEarlier && a.at != null -> "hamuj na ${a.at}"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} w apeksie"
        else -> null
    }

    override fun line(a: Advice): String? = when {
        !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "Hamuj na ${a.at} m · ${a.meters} później"
        a is Advice.BrakeEarlier && a.at != null && a.meters != null -> "Hamuj na ${a.at} m · ${a.meters} wcześniej"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} km/h w apeksie · ${a.kmh} więcej"
        a is Advice.Throttle && a.afterApex != null -> when {
            a.afterApex == 0 -> "Gaz w apeksie"
            a.afterApex > 0 -> "Gaz ${a.afterApex} m za apeksem"
            else -> "Gaz ${-a.afterApex} m przed apeksem"
        }
        else -> null
    }

    override fun result(moved: List<Moved>, done: Boolean, gainSeconds: Double?): String {
        val what = moved.joinToString(" · ") { m ->
            val by = m.by?.takeIf { gainSeconds != null }
            if (by == null) words(m.advice).replaceFirstChar(Char::uppercase) else movedBy(m.advice, by)
        }
        return when {
            !done -> "$what · jeszcze nie"
            gainSeconds == null -> "$what \u2713"
            else -> "$what \u2713 · ${signedSeconds(gainSeconds, comma = true)}"
        }
    }

    private fun movedBy(a: Advice, by: Double): String = when (a) {
        is Advice.Line -> "Apeks ${oneDecimal(abs(by), true)} m " + if (by >= 0) "ciaśniej" else "szerzej"
        is Advice.Throttle -> "Gaz ${abs(by).roundToInt()} m " + if (by >= 0) "wcześniej" else "później"
        is Advice.BrakeLater, is Advice.BrakeEarlier ->
            "Hamowanie ${abs(by).roundToInt()} m " + if (by >= 0) "później" else "wcześniej"
        is Advice.CarrySpeed -> "Apeks ${abs(by).roundToInt()} km/h " + if (by >= 0) "szybciej" else "wolniej"
    }

    /** "Dzisiaj, Dziewiątka, Jedynka, Czwórka" (UI-174 A). */
    override fun plan(turns: List<Int>): String = "Dzisiaj, " + turns.joinToString(", ") { turn(it) }

    override fun planLine(turns: List<Int>, seconds: Double?): String =
        "Dzisiaj: " +
            (turns.map { "T$it" } + listOfNotNull(seconds?.let { "\u2248 ${oneDecimal(it, true)} s do znalezienia" }))
            .joinToString(" \u00b7 ")

    override fun praise(corner: Corner, praise: Praise): String = turn(corner.label) + ", " + when (praise) {
        Praise.Worked -> "to zadziałało"
        Praise.BestYet -> "najlepiej dotąd"
    }

    /** "1, 32, 4" ("jeden, trzydzieści dwa, cztery"); "Najlepsze okrążenie, ..." for a new best. */
    override fun lapTime(millis: Long, best: Boolean): String {
        val (minutes, seconds, tenth) = lapTimeParts(millis)
        val time = if (minutes > 0) "$minutes, $seconds, $tenth" else "${seconds.toInt()}, $tenth"
        return if (best) "Najlepsze okrążenie, $time" else time
    }

    /** 150 as "100" "50" ("sto pięćdziesiąt"), 200 as "200" ("dwieście"): the hundreds, then the rest. */
    override fun numberKeys(word: String): List<String> {
        val n = word.toIntOrNull()?.takeUnless { word.startsWith('0') }
        if (n == null || n !in (CueClips.MaxNumber + 1)..MaxSpokenNumber) return listOf(word)
        val rest = n % Hundred
        return listOf((n - rest).toString()) + if (rest == 0) emptyList() else listOf(rest.toString())
    }

    /** A whole hundred is one word ("sto", "dwieście"). */
    override fun numberWords(key: String): Long =
        if (key.all(Char::isDigit) && key.toInt() % Hundred == 0 && key.toInt() > 0) 1L else super.numberWords(key)

    /** "Jedynka" to "Dziesiątka" for corners 1 to 10, as drivers say them; "Zakręt 11" from 11 on. */
    override fun turn(n: Int) = TurnNames.getOrNull(n - 1)?.takeIf { n >= 1 } ?: "Zakręt $n"

    private val TurnNames = listOf(
        "Jedynka", "Dwójka", "Trójka", "Czwórka", "Piątka",
        "Szóstka", "Siódemka", "Ósemka", "Dziewiątka", "Dziesiątka",
    )

    /** "metr", "metry" or "metrów" after [n]: 1 metr, 2 to 4 metry (not 12 to 14), else metrów. */
    internal fun metres(n: Int?): String {
        val m = n ?: return "metrów"
        return when {
            m == 1 -> "metr"
            m % Ten in Few && m % Hundred !in Teens -> "metry"
            else -> "metrów"
        }
    }

    private const val Ten = 10
    private const val Hundred = 100
    private const val MaxSpokenNumber = 999
    private val Few = 2..4
    private val Teens = 12..14
}
