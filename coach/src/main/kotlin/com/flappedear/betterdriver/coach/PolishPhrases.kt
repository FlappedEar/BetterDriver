package com.flappedear.betterdriver.coach

/**
 * What the coach says in Polish (FBBD-245): the same phrases as [Phrases], with the same numbers, in
 * the same order. A draft of the wording until Arek picks it (UI review round 24, UI-96 to UI-101):
 * corners as "Zakręt 3", the target first, then the change ("Zakręt 3, hamuj na 100, 10 później"),
 * lap times with "przecinek". Numbers stay digits, so text-to-speech says them in Polish and the
 * pre-rendered clips ([CueClips]) join them from parts: 150 as "100" "50" ("sto pięćdziesiąt").
 */
object PolishPhrases : PhraseBook {
    override fun corner(corner: Corner, advice: Advice): String = turn(corner.label) + ", " + advice(advice)

    private fun advice(a: Advice): String = when (a) {
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
    }

    override fun shortCorner(cue: Cue): String? {
        val a = cue.advice
        val target = when {
            a == null || !a.precise -> null
            a is Advice.BrakeLater && a.at != null -> "hamuj na ${a.at}"
            a is Advice.BrakeEarlier && a.at != null -> "hamuj na ${a.at}"
            a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} w apeksie"
            else -> null
        }
        val heard = cue.heardTurn
        return if (target == null || heard == null) null else "${turn(heard)}, $target".takeIf { it != cue.text }
    }

    override fun line(a: Advice): String? = when {
        !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "Hamuj na ${a.at} m · ${a.meters} później"
        a is Advice.BrakeEarlier && a.at != null && a.meters != null -> "Hamuj na ${a.at} m · ${a.meters} wcześniej"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} km/h w apeksie · ${a.kmh} więcej"
        else -> null
    }

    override fun praise(corner: Corner, praise: Praise): String = turn(corner.label) + ", " + when (praise) {
        Praise.Worked -> "to zadziałało"
        Praise.BestYet -> "najlepiej dotąd"
    }

    /** "1 32 przecinek 4" ("jeden trzydzieści dwa przecinek cztery"); "Najlepsze okrążenie, ..." for a new best. */
    override fun lapTime(millis: Long, best: Boolean): String {
        val (minutes, seconds, tenth) = lapTimeParts(millis)
        val time = if (minutes > 0) "$minutes $seconds przecinek $tenth" else "${seconds.toInt()} przecinek $tenth"
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

    private fun turn(n: Int) = "Zakręt $n"

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
