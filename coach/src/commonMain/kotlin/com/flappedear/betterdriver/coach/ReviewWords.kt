package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence

/**
 * The words of the cue review (FBBD-99) in the language the cues were said in (FBBD-245, UI-100 B):
 * what a cue led to, how far it could be trusted, and the session's summary.
 */
@Suppress("TooManyFunctions") // One function per sentence of the review.
internal interface ReviewWords {
    /** Whether numbers take a decimal comma, as in Polish. */
    val comma: Boolean

    /** "12 cues", "1 cue". */
    fun cues(n: Int): String

    /** "No cues". */
    val noCues: String

    /** "5 of 8 corners better". */
    fun better(improved: Int, judged: Int): String

    /** "High confidence". */
    fun confidence(c: Confidence): String

    /** A logged doubt's [code] in words. */
    fun doubt(code: String): String

    /** "Apex 58 km/h, " before the change. */
    fun apex(kmh: Int): String

    /** "Braked at 108 m, " before the change. */
    fun brakedAt(m: Int): String

    /** "3 more than before" or "3 less than before"; with [m], "8 m later than before" for a braking point. */
    fun moved(n: Int, m: Boolean): String

    /** Under the threshold: "at the same point as before" ([m]) or "same as before". */
    fun same(m: Boolean): String

    /** "Braked 9 m later", "Braked later" without [n]; [later] false for earlier; null [later] for the same point. */
    fun braked(later: Boolean?, n: Int?): String

    /** "4 m short of best" ([short]), "4 m past best", without [n] "short of best"; null [short] for at best. */
    fun toBestPoint(short: Boolean?, n: Int?): String

    /** "Minimum speed 3 km/h higher", without [n] "Minimum speed higher"; null [higher] for the same speed. */
    fun minSpeed(higher: Boolean?, n: Int?): String

    /** "2 km/h under best", "over best"; null [under] for at best. */
    fun toBestSpeed(under: Boolean?, n: Int?): String

    /** "0.12 s gained", "time lost" without [seconds]. */
    fun time(gained: Boolean, seconds: Double?): String

    /** "0.21 s faster than before the cue" ([worked]) or "than your best until then". */
    fun faster(seconds: Double, worked: Boolean): String

    companion object {
        fun of(language: CoachLanguage): ReviewWords = when (language) {
            CoachLanguage.English -> EnglishReview
            CoachLanguage.Polish -> PolishReview
        }
    }
}

@Suppress("TooManyFunctions") // One function per sentence of the review.
internal object EnglishReview : ReviewWords {
    override val comma = false

    override fun cues(n: Int) = if (n == 1) "1 cue" else "$n cues"

    override val noCues = "No cues"

    override fun better(improved: Int, judged: Int) = "$improved of $judged corners better"

    override fun confidence(c: Confidence) = "${c.label} confidence"

    override fun doubt(code: String) = CueDoubts.text(code)

    override fun apex(kmh: Int) = "Apex $kmh km/h, "

    override fun brakedAt(m: Int) = "Braked at $m m, "

    override fun moved(n: Int, m: Boolean): String {
        val (up, down) = if (m) "m later" to "m earlier" else "more" to "less"
        return if (n > 0) "$n $up than before" else "${-n} $down than before"
    }

    override fun same(m: Boolean) = if (m) "at the same point as before" else "same as before"

    override fun braked(later: Boolean?, n: Int?) = when (later) {
        null -> "Braked at the same point"
        true -> "Braked ${amount(n, "m")}later"
        false -> "Braked ${amount(n, "m")}earlier"
    }

    override fun toBestPoint(short: Boolean?, n: Int?) = when (short) {
        null -> "at the best point"
        true -> "${amount(n, "m")}short of best"
        false -> "${amount(n, "m")}past best"
    }

    override fun minSpeed(higher: Boolean?, n: Int?) = when (higher) {
        null -> "Same minimum speed"
        true -> "Minimum speed ${amount(n, "km/h")}higher"
        false -> "Minimum speed ${amount(n, "km/h")}lower"
    }

    override fun toBestSpeed(under: Boolean?, n: Int?) = when (under) {
        null -> "at best"
        true -> "${amount(n, "km/h")}under best"
        false -> "${amount(n, "km/h")}over best"
    }

    override fun time(gained: Boolean, seconds: Double?): String {
        val s = seconds?.let { decimals(it, 2, comma) + " s" } ?: "time"
        return if (gained) "$s gained" else "$s lost"
    }

    override fun faster(seconds: Double, worked: Boolean) =
        "${decimals(seconds, 2, comma)} s faster than " + if (worked) "before the cue" else "your best until then"

    /** "9 m " with a number, nothing without: the sentence reads the same either way. */
    private fun amount(n: Int?, unit: String) = if (n != null) "$n $unit " else ""
}

/** The review in Polish, with a decimal comma: "Hamowanie na 108 m, 8 m później niż poprzednio · zysk 0,12 s". */
@Suppress("TooManyFunctions") // One function per sentence of the review.
internal object PolishReview : ReviewWords {
    override val comma = true

    override fun cues(n: Int) = "$n " + plural(n, "wskazówka", "wskazówki", "wskazówek")

    override val noCues = "Brak wskazówek"

    override fun better(improved: Int, judged: Int) = "$improved z $judged zakrętów lepiej"

    override fun confidence(c: Confidence) = "Pewność " + when (c) {
        Confidence.HIGH -> "wysoka"
        Confidence.MEDIUM -> "średnia"
        Confidence.LOW -> "niska"
    }

    override fun doubt(code: String) = when (code) {
        CueDoubts.GNSS_GAP -> "brak pozycji w zakręcie"
        CueDoubts.VERY_LOW_SPEED -> "prawie zatrzymanie w zakręcie"
        CueDoubts.WEAK_INTERPOLATION -> "pozycje daleko od siebie"
        CueDoubts.SPEED_NOT_MEASURED -> "prędkość wyliczona z pozycji, nie zmierzona"
        CueDoubts.SPREAD -> "w granicach zwykłego rozrzutu między przejazdami"
        CueDoubts.FEW_PASSES -> "za mało przejazdów, by ocenić"
        CueDoubts.IDENTITY -> "zakręt nie dopasowany do zakrętów toru"
        else -> code
    }

    override fun apex(kmh: Int) = "Apeks $kmh km/h, "

    override fun brakedAt(m: Int) = "Hamowanie na $m m, "

    override fun moved(n: Int, m: Boolean): String {
        val (up, down) = if (m) "m później" to "m wcześniej" else "więcej" to "mniej"
        return if (n > 0) "$n $up niż poprzednio" else "${-n} $down niż poprzednio"
    }

    override fun same(m: Boolean) = if (m) "w tym samym miejscu co poprzednio" else "tyle samo co poprzednio"

    override fun braked(later: Boolean?, n: Int?) = when (later) {
        null -> "Hamowanie w tym samym miejscu"
        true -> "Hamowanie ${amount(n, "m")}później"
        false -> "Hamowanie ${amount(n, "m")}wcześniej"
    }

    override fun toBestPoint(short: Boolean?, n: Int?) = when (short) {
        null -> "w najlepszym miejscu"
        true -> "${amount(n, "m")}przed najlepszym"
        false -> "${amount(n, "m")}za najlepszym"
    }

    override fun minSpeed(higher: Boolean?, n: Int?) = when (higher) {
        null -> "Ta sama prędkość minimalna"
        true -> "Prędkość minimalna wyższa${by(n, "km/h")}"
        false -> "Prędkość minimalna niższa${by(n, "km/h")}"
    }

    override fun toBestSpeed(under: Boolean?, n: Int?) = when (under) {
        null -> "równa najlepszej"
        true -> "${amount(n, "km/h")}poniżej najlepszej"
        false -> "${amount(n, "km/h")}powyżej najlepszej"
    }

    override fun time(gained: Boolean, seconds: Double?): String {
        val s = seconds?.let { " " + decimals(it, 2, comma) + " s" } ?: " czasu"
        return (if (gained) "zysk" else "strata") + s
    }

    override fun faster(seconds: Double, worked: Boolean) =
        "${decimals(seconds, 2, comma)} s szybciej niż " + if (worked) "przed wskazówką" else "dotychczas najlepiej"

    private fun amount(n: Int?, unit: String) = if (n != null) "$n $unit " else ""

    private fun by(n: Int?, unit: String) = if (n != null) " o $n $unit" else ""

    /** 1 [one], 2 to 4 [few] (not 12 to 14), else [many]. */
    private fun plural(n: Int, one: String, few: String, many: String) = when {
        n == 1 -> one
        n % Ten in Few && n % Hundred !in Teens -> few
        else -> many
    }

    private const val Ten = 10
    private const val Hundred = 100
    private val Few = 2..4
    private val Teens = 12..14
}
