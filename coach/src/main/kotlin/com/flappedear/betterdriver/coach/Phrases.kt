package com.flappedear.betterdriver.coach

import kotlin.math.roundToInt

/**
 * What the coach says, in one language (FBBD-245): every phrase of a cue, the coach line on the track
 * screen, and how long a phrase takes to say. [Phrases] is English, [PolishPhrases] Polish.
 */
@Suppress("TooManyFunctions") // One phrase for each thing the coach says or shows.
interface PhraseBook {
    /** A corner cue: the corner, then [advice], then [also] when there is a second thing to change (UI-170 B). */
    fun corner(corner: Corner, advice: Advice, also: Advice? = null): String =
        turn(corner.label) + ", " + listOfNotNull(advice, also).joinToString(", ") { words(it) }

    /** How a corner is said: "Turn 3". */
    fun turn(n: Int): String

    /** The words of [a] in a corner cue: "brake at 100, 10 later", "tighter at the apex". */
    fun words(a: Advice): String

    /**
     * What a pass did after a cue, for the coach line (UI-173 A): what it moved, against the pass
     * before the cue ([moved]), then whether it did what was asked and the time it gave ([gainSeconds],
     * positive faster; null when the numbers are not trusted): "Apex 1.8 m tighter ✓ · −0.09 s",
     * "Apex 0.4 m tighter · not yet".
     */
    fun result(moved: List<Moved>, done: Boolean, gainSeconds: Double?): String

    /** The out-lap cue (UI-174 A): "Today, turns 9, 1 and 4". */
    fun plan(turns: List<Int>): String

    /** Its coach line: "Today: T9 · T1 · T4 · ≈ 1.0 s to find"; [seconds] null when not known. */
    fun planLine(turns: List<Int>, seconds: Double?): String

    /** [cue] with its target only, for a straight too short for the whole cue; null if there is none. */
    fun shortCorner(cue: Cue): String?

    /** The coach line on the track screen for advice with its target; null if the line is the words said. */
    fun line(a: Advice): String?

    fun praise(corner: Corner, praise: Praise): String

    fun lapTime(millis: Long, best: Boolean): String

    /** The clips the number [word] is said from ([CueClips]): itself, or for 100 to 999 its parts. */
    fun numberKeys(word: String): List<String>

    /** How many words the number clip [key] is said as, for [durationMicros]. */
    fun numberWords(key: String): Long =
        if (key.all(Char::isDigit) && key.length >= 2 && key.toInt() > SpokenAsOneWord) 2L else 1L

    /**
     * About how long text-to-speech takes to say [text] at normal rate: a fixed start plus a time per
     * word, a number counted as the words it is said in. An estimate; the audio delay setting covers lag.
     */
    fun durationMicros(text: String): Long {
        val words = text.split(' ', ',').filter { it.isNotBlank() }.sumOf { w ->
            if (w.all(Char::isDigit)) numberKeys(w).sumOf(::numberWords) else 1L
        }
        return StartMicros + words * WordMicros
    }

    companion object {
        /** The phrases of [language]. */
        fun of(language: CoachLanguage): PhraseBook = when (language) {
            CoachLanguage.English -> Phrases
            CoachLanguage.Polish -> PolishPhrases
        }

        private const val SpokenAsOneWord = 20
        private const val StartMicros = 200_000L
        private const val WordMicros = 400_000L
    }
}

/**
 * What one part of a cue led to, for its result on the coach line (UI-173 A): the [advice] it was
 * for, and how far the pass after it moved that way from the pass before ([by]: metres tighter,
 * metres earlier on throttle, metres later braking, km/h more at the apex), null when not measured.
 */
data class Moved(val advice: Advice, val by: Double?)

/** "−0.09 s" for time gained, "+0.05 s" for time lost, with [comma] as the decimal mark. */
internal fun signedSeconds(gainSeconds: Double, comma: Boolean): String {
    val s = String.format(java.util.Locale.ROOT, "%.2f", kotlin.math.abs(gainSeconds))
    return (if (gainSeconds >= 0) "\u2212" else "+") + (if (comma) s.replace('.', ',') else s) + " s"
}

/** [n] to one decimal place, with [comma] as the decimal mark. */
internal fun oneDecimal(n: Double, comma: Boolean): String =
    String.format(java.util.Locale.ROOT, "%.1f", n).let { if (comma) it.replace('.', ',') else it }

/** A lap time in tenths, as minutes, zero-padded seconds and the tenth: 92 400 ms is (1, "32", 4). */
internal fun lapTimeParts(millis: Long): Triple<Long, String, Long> {
    val tenths = (millis + HalfTenth) / MillisPerTenth
    val seconds = "%02d".format(tenths % TenthsPerMinute / TenthsPerSecond)
    return Triple(tenths / TenthsPerMinute, seconds, tenths % TenthsPerSecond)
}

private const val HalfTenth = 50L
private const val MillisPerTenth = 100L
private const val TenthsPerSecond = 10L
private const val TenthsPerMinute = 600L

/**
 * What the coach says in English (FBBD-97): short phrases, numbers allowed (Arek, FBBD-6) when the
 * evidence is strong enough for them (FBBD-177). Corners are named by the track's corner numbers
 * (FBBD-172), the ones the lap analysis shows.
 *
 * A corner cue says the target first, then the change from the last pass (FBBD-242, Arek's picks
 * UI-87 C and UI-88 C): "Turn 3, brake at 100, 10 later", "Turn 3, 60 at the apex, 5 more". Advice
 * without a target (logged before FBBD-242) is said as before: "Turn 3, brake 10 metres later".
 * The line and the throttle (FBBD-307, round 43): "Turn 1, tighter at the apex" without a number
 * (UI-168 B), "Turn 1, throttle 5 after the apex", the target only (UI-169 C), and at most two in
 * one cue (UI-170 B): "Turn 1, tighter at the apex, throttle 5 after the apex".
 */
@Suppress("TooManyFunctions", "CyclomaticComplexMethod") // Each phrase with its few forms.
object Phrases : PhraseBook {
    override fun turn(n: Int) = "Turn $n"

    /** The advice part of a corner cue: "brake at 100, 10 later", "tighter at the apex" (UI-168 B). */
    override fun words(a: Advice): String = when (a) {
        is Advice.BrakeLater -> when {
            !a.precise -> "brake a little later"
            a.at != null -> "brake at ${a.at}, ${a.meters} later"
            else -> "brake ${a.meters} metres later"
        }
        is Advice.BrakeEarlier -> when {
            a.precise && a.at != null && a.meters != null -> "brake at ${a.at}, ${a.meters} earlier"
            else -> "brake earlier"
        }
        is Advice.CarrySpeed -> when {
            !a.precise -> "carry a little more speed"
            a.goalKmh != null -> "${a.goalKmh} at the apex, ${a.kmh} more"
            else -> "carry ${a.kmh} more"
        }
        is Advice.Line -> if (a.tighterMeters >= 0) "tighter at the apex" else "wider at the apex"
        is Advice.Throttle -> {
            val after = a.afterApex
            when {
                !a.precise || after == null -> if (a.earlierMeters >= 0) "throttle earlier" else "throttle later"
                after == 0 -> "throttle at the apex"
                after > 0 -> "throttle $after after the apex"
                else -> "throttle ${-after} before the apex"
            }
        }
    }

    /**
     * [cue] with its target only, for a straight too short for the whole cue (FBBD-242): "Turn 3,
     * brake at 100", "Turn 3, 60 at the apex"; a cue with two things to change says the first alone.
     * Null when the cue has no target or is already short.
     */
    override fun shortCorner(cue: Cue): String? {
        val a = cue.advice
        val target = when {
            // Two in one cue (UI-170 B): the first alone.
            a != null && cue.also != null -> shortTarget(a) ?: words(a)
            else -> shortTarget(a)
        }
        val turn = cue.heardTurn
        return if (target == null || turn == null) null else "Turn $turn, $target".takeIf { it != cue.text }
    }

    /** [a]'s target alone: "brake at 100", "60 at the apex"; null without one, or when [a] is no longer. */
    private fun shortTarget(a: Advice?): String? = when {
        a == null || !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "brake at ${a.at}"
        a is Advice.BrakeEarlier && a.at != null -> "brake at ${a.at}"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} at the apex"
        else -> null
    }

    /**
     * The coach line on the track screen for advice with its target (UI-87 C, UI-88 C):
     * "Brake at 100 m · 10 later", "60 km/h at the apex · 5 more". Null for any other advice, whose
     * line is the spoken words.
     */
    override fun line(a: Advice): String? = when {
        !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "Brake at ${a.at} m · ${a.meters} later"
        a is Advice.BrakeEarlier && a.at != null && a.meters != null -> "Brake at ${a.at} m · ${a.meters} earlier"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} km/h at the apex · ${a.kmh} more"
        a is Advice.Throttle && a.afterApex != null -> when {
            a.afterApex == 0 -> "Throttle at the apex"
            a.afterApex > 0 -> "Throttle ${a.afterApex} m after the apex"
            else -> "Throttle ${-a.afterApex} m before the apex"
        }
        else -> null
    }

    override fun result(moved: List<Moved>, done: Boolean, gainSeconds: Double?): String {
        val what = moved.joinToString(" · ") { m ->
            val by = m.by?.takeIf { gainSeconds != null }
            if (by == null) words(m.advice).replaceFirstChar(Char::uppercase) else movedBy(m.advice, by)
        }
        return when {
            !done -> "$what · not yet"
            gainSeconds == null -> "$what \u2713"
            else -> "$what \u2713 · ${signedSeconds(gainSeconds, comma = false)}"
        }
    }

    private fun movedBy(a: Advice, by: Double): String = when (a) {
        is Advice.Line -> "Apex ${oneDecimal(kotlin.math.abs(by), false)} m " + if (by >= 0) "tighter" else "wider"
        is Advice.Throttle -> "Throttle ${kotlin.math.abs(by).roundToInt()} m " + if (by >= 0) "earlier" else "later"
        is Advice.BrakeLater, is Advice.BrakeEarlier ->
            "Braked ${kotlin.math.abs(by).roundToInt()} m " + if (by >= 0) "later" else "earlier"
        is Advice.CarrySpeed -> "Apex ${kotlin.math.abs(by).roundToInt()} km/h " + if (by >= 0) "faster" else "slower"
    }

    override fun plan(turns: List<Int>): String = when (turns.size) {
        // "Turn" as in a corner cue: one clip for both ([CueClips]).
        1 -> "Today, ${turn(turns[0])}"
        else -> "Today, turns " + turns.dropLast(1).joinToString(", ") + " and " + turns.last()
    }

    override fun planLine(turns: List<Int>, seconds: Double?): String =
        "Today: " + (turns.map { "T$it" } + listOfNotNull(seconds?.let { "\u2248 ${oneDecimal(it, false)} s to find" }))
            .joinToString(" \u00b7 ")

    /** "Turn 3, that worked" or "Turn 3, best yet" (FBBD-243, UI-93 C). */
    override fun praise(corner: Corner, praise: Praise): String = "Turn ${corner.label}, " + when (praise) {
        Praise.Worked -> "that worked"
        Praise.BestYet -> "best yet"
    }

    /** "1 32 point 4", read by text-to-speech as "one thirty-two point four"; "Best lap, ..." for a new best. */
    override fun lapTime(millis: Long, best: Boolean): String {
        val (minutes, seconds, tenth) = lapTimeParts(millis)
        val time = if (minutes > 0) "$minutes $seconds point $tenth" else "${seconds.toInt()} point $tenth"
        return if (best) "Best lap, $time" else time
    }

    /** 150 as "1" "50" ("one fifty"), 105 as "1" "oh" "5", 200 as "2" "hundred" ([CueClips]). */
    override fun numberKeys(word: String): List<String> = CueClips.numberKeys(word)
}
